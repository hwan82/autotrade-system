package com.autotrade.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 카카오톡 "나에게 보내기" 채널(무료, 본인 나와의 채팅방으로만 간다). 유료 알림톡(BIZ)은 이 라이브러리에 없다.
 *
 * <p>[사실] 근거: 카카오디벨로퍼스 카카오톡 메시지 REST API "나에게 기본 템플릿으로 메시지 보내기"
 * ({@value #MEMO_URL}, {@code Authorization: Bearer}, form 본문 {@code template_object}, 성공 시 {@code result_code} 0, 동의항목
 * {@code talk_message}), 메시지 템플릿 "기본 템플릿"(텍스트 {@code text} 최대 200자, {@code link} 필수이며 {@code web_url}·
 * {@code mobile_web_url} 중 하나 이상, 앱에 등록한 웹 도메인과 일치), 카카오 로그인 REST API "토큰 갱신하기"({@value #TOKEN_URL},
 * {@code grant_type=refresh_token}, {@code client_id}=REST API 키, 클라이언트 시크릿이 켜져 있으면 {@code client_secret},
 * 응답 {@code access_token}·{@code expires_in}, 리프레시 토큰은 남은 기간 1개월 미만일 때만 새로 옴), "토큰 정보 보기"
 * (-401, HTTP 401 = 토큰이 잘못됐거나 만료, 갱신 필요). 2026-10-16 확인.
 *
 * <p>토큰 자동 갱신: 액세스 토큰 만료 {@value #REFRESH_MARGIN_MINUTES}분 전부터는 보내기 전에 갱신한다. 이 미리 갱신이 일시
 * 장애로 실패해도 아직 만료 전이면 지금 토큰으로 보낸다. 그래도 발송이 HTTP 401이면
 * (이번 발송에서 아직 갱신을 시도하지 않았을 때만) 한 번 갱신하고 한 번 더 보낸다. 갱신이 400·401이면 리프레시 토큰이 만료·폐기된 것이라 사용자가 다시 동의해 토큰을 받아야 한다
 * ({@code kakao-refresh-rejected}). 그 밖의 실패는 그 알림만 실패로 돌려주고 {@link Notifier}가 다음 채널로 넘긴다.
 *
 * <p>갱신이 거절되면 그 리프레시 토큰을 기억하고, 같은 토큰인 동안은 카카오에 다시 묻지 않고 {@value #RECONSENT_REQUIRED}로 바로
 * 실패한다(PR #127 QA R1). 거절을 처음 알린 발송만 {@value #REFRESH_REJECTED}를 돌려주므로 연결하는 쪽은 이 코드를 한 번 보고
 * "카카오 재동의 필요"를 한 번만 알린다({@link NotificationQueue}). 저장소({@link KakaoTokenStore#latest})에 다른 리프레시 토큰이
 * 들어오면(다른 프로세스가 받은 새 토큰) 그 토큰으로 갈아타고 거절 상태를 푼다.
 *
 * <p>여러 스레드가 같이 불러도 토큰 상태가 꼬이지 않게 {@link #send}는 직렬로 돈다.
 */
public final class KakaoMemoChannel implements Channel {

    static final String MEMO_URL = "https://kapi.kakao.com/v2/api/talk/memo/default/send";
    static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    static final int MAX_TEXT = 200;
    static final long REFRESH_MARGIN_MINUTES = 5;
    /** 리프레시 토큰이 거절된 첫 발송의 사유 코드. 사용자가 다시 동의해 토큰을 받아야 한다. */
    public static final String REFRESH_REJECTED = "kakao-refresh-rejected";
    /** 거절된 리프레시 토큰을 아직 들고 있어 카카오에 묻지 않고 실패한 발송의 사유 코드. */
    public static final String RECONSENT_REQUIRED = "kakao-reconsent-required";
    /**
     * 갱신 응답 {@code expires_in} 상한. 넘으면 이상 응답으로 본다(PR #127 QA R2: 아주 큰 값이 시각 계산에서 런타임 예외로 새지
     * 않게). 문서 예시는 약 12시간이고 보장 값은 없어 {@code [가정]} 여유를 둔 7일이다.
     */
    static final Duration MAX_EXPIRES_IN = Duration.ofDays(7);
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(REFRESH_MARGIN_MINUTES);
    private static final String FORM = "application/x-www-form-urlencoded;charset=utf-8";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(KakaoMemoChannel.class.getName());

    private final String restApiKey;
    private final String clientSecret;
    private final String linkUrl;
    private final KakaoTokenStore store;
    private final HttpTransport http;
    private final Clock clock;
    private KakaoToken token;
    /** 갱신이 거절된 리프레시 토큰. 거절 상태가 아니면 null. */
    private String rejectedRefreshToken;

    /**
     * @param restApiKey 앱 REST API 키(갱신 요청의 {@code client_id})
     * @param clientSecret 클라이언트 시크릿. 앱에서 끈 경우 빈 문자열
     * @param linkUrl 메시지 링크. 앱에 등록한 웹 도메인의 http(s) URL이어야 한다(카카오 템플릿 규칙)
     * @param token 처음 토큰
     */
    public KakaoMemoChannel(String restApiKey, String clientSecret, String linkUrl, KakaoToken token,
            KakaoTokenStore store, HttpTransport http, Clock clock) {
        Objects.requireNonNull(restApiKey, "restApiKey");
        if (restApiKey.isBlank()) {
            throw new IllegalArgumentException("카카오 REST API 키가 비었습니다");
        }
        this.restApiKey = restApiKey.strip();
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret").strip();
        this.linkUrl = checkLink(linkUrl);
        this.token = Objects.requireNonNull(token, "token");
        this.store = Objects.requireNonNull(store, "store");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static String checkLink(String linkUrl) {
        Objects.requireNonNull(linkUrl, "linkUrl");
        URI uri;
        try {
            uri = URI.create(linkUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("카카오 메시지 링크 형식이 틀렸습니다");
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null) {
            throw new IllegalArgumentException("카카오 메시지 링크는 등록한 웹 도메인의 http(s) URL이어야 합니다(쿼리 없음)");
        }
        return uri.toString();
    }

    @Override
    public String name() {
        return "kakao";
    }

    @Override
    public synchronized SendResult send(Notification notification) throws InterruptedException {
        adoptLatest();
        if (token.refreshToken().equals(rejectedRefreshToken)) {
            return SendResult.failed(RECONSENT_REQUIRED);
        }
        Instant now = clock.instant();
        boolean refreshTried = false;
        if (!now.isBefore(token.accessExpiresAt().minus(REFRESH_MARGIN))) {
            String refreshed = refresh();
            refreshTried = true;
            // 미리 갱신이 일시 장애로 실패해도 아직 만료 전이면 지금 토큰으로 보낸다(PR #127 architect R1). 거절이면 멈춘다
            if (refreshed != null && (refreshed.equals(REFRESH_REJECTED) || !now.isBefore(token.accessExpiresAt()))) {
                return SendResult.failed(refreshed);
            }
        }
        String form = "template_object=" + URLEncoder.encode(template(notification), StandardCharsets.UTF_8);
        SendResult first = post(form);
        // 한 발송에서 갱신 시도는 한 번뿐이다: 미리 갱신을 시도했으면(성공이든 일시 장애든) 401에 다시 갱신하지 않는다
        // (PR #127 risk-ops R3·R5, architect F2). 그래서 HTTP는 최대 세 번이다
        if (first.sent() || !first.detail().equals("http-401") || refreshTried) {
            return first;
        }
        String refreshed = refresh();
        if (refreshed != null) {
            return SendResult.failed(refreshed);
        }
        return post(form);
    }

    /** 저장소에 더 늦게 만료되는 토큰이나(다른 프로세스가 갱신) 거절된 것과 다른 리프레시 토큰이 있으면 갈아탄다. */
    private void adoptLatest() {
        Optional<KakaoToken> latest;
        try {
            latest = store.latest();
        } catch (RuntimeException e) {
            return; // 저장소를 못 읽어도 지금 토큰으로 보낸다
        }
        if (latest == null || latest.isEmpty()) {
            return;
        }
        KakaoToken other = latest.get();
        boolean newer = other.accessExpiresAt().isAfter(token.accessExpiresAt());
        boolean freshRefresh = rejectedRefreshToken != null && !other.refreshToken().equals(rejectedRefreshToken);
        if (newer || freshRefresh) {
            token = other;
            if (!token.refreshToken().equals(rejectedRefreshToken)) {
                rejectedRefreshToken = null;
            }
        }
    }

    private SendResult post(String form) throws InterruptedException {
        HttpTransport.HttpResult r;
        try {
            r = http.post(URI.create(MEMO_URL),
                    Map.of("Authorization", "Bearer " + token.accessToken(), "Content-Type", FORM), form);
        } catch (IOException e) {
            return SendResult.failed(Failures.of(e));
        }
        if (r.status() != 200) {
            return SendResult.failed("http-" + r.status());
        }
        JsonNode code = parse(r.body()).path("result_code");
        return code.isInt() && code.intValue() == 0 ? SendResult.ok() : SendResult.failed("kakao-result-code");
    }

    /** 토큰을 갱신한다. 성공하면 null, 실패하면 사유 코드. */
    private String refresh() throws InterruptedException {
        StringBuilder form = new StringBuilder("grant_type=refresh_token")
                .append("&client_id=").append(URLEncoder.encode(restApiKey, StandardCharsets.UTF_8))
                .append("&refresh_token=").append(URLEncoder.encode(token.refreshToken(), StandardCharsets.UTF_8));
        if (!clientSecret.isEmpty()) {
            form.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
        }
        HttpTransport.HttpResult r;
        try {
            r = http.post(URI.create(TOKEN_URL), Map.of("Content-Type", FORM), form.toString());
        } catch (IOException e) {
            return "kakao-refresh-" + Failures.of(e);
        }
        if (r.status() == 400 || r.status() == 401) {
            rejectedRefreshToken = token.refreshToken();
            return REFRESH_REJECTED;
        }
        if (r.status() != 200) {
            return "kakao-refresh-http-" + r.status();
        }
        JsonNode body = parse(r.body());
        JsonNode access = body.path("access_token");
        JsonNode expiresIn = body.path("expires_in");
        if (!access.isTextual() || access.textValue().isBlank() || !expiresIn.canConvertToLong() || expiresIn.longValue() <= 0
                || expiresIn.longValue() > MAX_EXPIRES_IN.toSeconds()) {
            return "kakao-refresh-bad-response";
        }
        JsonNode rotated = body.path("refresh_token");
        boolean newRefresh = rotated.isTextual() && !rotated.textValue().isBlank();
        token = new KakaoToken(access.textValue(), newRefresh ? rotated.textValue() : token.refreshToken(),
                clock.instant().plusSeconds(expiresIn.longValue()));
        try {
            store.save(token);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "카카오 갱신 토큰을 저장하지 못함(새 리프레시 토큰 "
                    + (newRefresh ? "있음, 재기동 뒤 갱신이 실패할 수 있음" : "없음") + ")");
        }
        return null;
    }

    /** 텍스트 템플릿. 본문은 200자에서 자르고 끝에 {@code …}를 붙인다(코드 포인트 기준). */
    String template(Notification notification) {
        ObjectNode t = JSON.createObjectNode();
        t.put("object_type", "text");
        t.put("text", truncate(notification.plainText(), MAX_TEXT));
        ObjectNode link = t.putObject("link");
        link.put("web_url", linkUrl);
        link.put("mobile_web_url", linkUrl);
        return t.toString();
    }

    static String truncate(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node == null ? JSON.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }

    /** 갱신이 거절된 리프레시 토큰을 들고 있는지(사용자 재동의가 필요한 상태). */
    public synchronized boolean reconsentRequired() {
        return rejectedRefreshToken != null && token.refreshToken().equals(rejectedRefreshToken);
    }

    /** 시험용: 지금 들고 있는 토큰. */
    synchronized KakaoToken currentToken() {
        return token;
    }

    @Override
    public String toString() {
        return "KakaoMemoChannel[restApiKey=****, token=****]";
    }
}
