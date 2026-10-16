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
 * <p>토큰 자동 갱신: 액세스 토큰 만료 {@value #REFRESH_MARGIN_MINUTES}분 전부터는 보내기 전에 갱신한다. 그래도 발송이 HTTP 401이면
 * 한 번 갱신하고 한 번 더 보낸다. 갱신이 400·401이면 리프레시 토큰이 만료·폐기된 것이라 사용자가 다시 동의해 토큰을 받아야 한다
 * ({@code kakao-refresh-rejected}). 그 밖의 실패는 그 알림만 실패로 돌려주고 {@link Notifier}가 다음 채널로 넘긴다.
 *
 * <p>여러 스레드가 같이 불러도 토큰 상태가 꼬이지 않게 {@link #send}는 직렬로 돈다.
 */
public final class KakaoMemoChannel implements Channel {

    static final String MEMO_URL = "https://kapi.kakao.com/v2/api/talk/memo/default/send";
    static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    static final int MAX_TEXT = 200;
    static final long REFRESH_MARGIN_MINUTES = 5;
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
        if (!clock.instant().isBefore(token.accessExpiresAt().minus(REFRESH_MARGIN))) {
            String refreshed = refresh();
            if (refreshed != null) {
                return SendResult.failed(refreshed);
            }
        }
        String form = "template_object=" + URLEncoder.encode(template(notification), StandardCharsets.UTF_8);
        SendResult first = post(form);
        if (first.sent() || !first.detail().equals("http-401")) {
            return first;
        }
        String refreshed = refresh();
        if (refreshed != null) {
            return SendResult.failed(refreshed);
        }
        return post(form);
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
            return "kakao-refresh-rejected";
        }
        if (r.status() != 200) {
            return "kakao-refresh-http-" + r.status();
        }
        JsonNode body = parse(r.body());
        JsonNode access = body.path("access_token");
        JsonNode expiresIn = body.path("expires_in");
        if (!access.isTextual() || access.textValue().isBlank() || !expiresIn.canConvertToLong() || expiresIn.longValue() <= 0) {
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

    /** 시험용: 지금 들고 있는 토큰. */
    synchronized KakaoToken currentToken() {
        return token;
    }

    @Override
    public String toString() {
        return "KakaoMemoChannel[restApiKey=****, token=****]";
    }
}
