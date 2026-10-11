package com.autotrade.broker.kis;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * 한국투자 REST 시세 조회 호출기(읽기 전용). 계좌와 상관없는 시세 조회 TR만 부른다.
 *
 * <p>`[사실]` 근거: koreainvestment/open-trading-api(커밋 2082791, 2026-10-10 확인, 요약 docs/research/kis-open-api-spec.md 3장)
 * <ul>
 *   <li>조회는 {@code GET https://openapi.koreainvestment.com:9443<경로>?<입력값>}, 헤더 {@code authorization: Bearer <토큰>},
 *       {@code appkey}, {@code appsecret}, {@code tr_id}, {@code custtype: P}(examples_user/kis_auth.py {@code _url_fetch}).</li>
 *   <li>응답 본문 {@code rt_cd}가 "0"이면 정상, 아니면 {@code msg_cd}·{@code msg1}. 토큰 만료(EGW00123)는 HTTP 200으로도 온다.</li>
 *   <li>한도: 실전 초당 20건(legacy/rest 주석 "REST: 1초당 20건 제한", 공식 kis_auth.py 실전 호출 간격 0.05초).
 *       여유를 두어 0.1초 간격으로 부른다(공식 legacy 샘플과 같은 값).</li>
 * </ul>
 *
 * <p>안전 경계(안전 불변식 8, MD-01): 아래 허용 목록의 (경로, TR) 쌍만 GET으로 부른다. 주문({@code /trading/order-*}),
 * 잔고·체결 조회({@code /trading/inquire-*}), 휴장일 조회({@code chk-holiday}, 공식 안내 "1일 1회") 같은 경로와
 * 실전 거래 TR({@code TTTC…}, {@code CTSC…})은 부르기 전에 거절한다. 목록을 넓히는 변경은 risk-ops 검토 대상이다.
 * 이 호출기는 POST·계좌번호 헤더 코드를 갖지 않는다.
 *
 * <p>헤더에 앱 시크릿이 실리므로 요청 헤더·응답 본문을 로그나 예외 메시지에 넣지 않는다(안전 불변식 9).
 */
public final class KisRestClient {

    static final URI BASE = URI.create("https://openapi.koreainvestment.com:9443");
    static final Duration DEFAULT_INTERVAL = Duration.ofMillis(100);

    /** 조회 전용으로 허용하는 경로와 그 경로의 TR. 공식 예제의 실전·모의 공통 TR. */
    static final Map<String, String> READ_ONLY = Map.of(
            "/uapi/domestic-stock/v1/quotations/inquire-price", "FHKST01010100",
            "/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn", "FHKST01010200",
            "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice", "FHKST03010100",
            "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice", "FHKST03010200",
            "/uapi/overseas-price/v1/quotations/price", "HHDFS00000300",
            "/uapi/overseas-price/v1/quotations/dailyprice", "HHDFS76240000");

    /** 호출 간격 대기. 운영은 {@link Thread#sleep}, 테스트는 기록만 한다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    /** 한국투자가 오류 코드로 답했다. 메시지에는 TR과 결과 코드, 고정 안내만 들어간다. */
    public static final class KisApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean rateLimited;

        KisApiException(String trId, KisErrorCode code) {
            super("한국투자 " + trId + " 실패: " + code.describe());
            this.code = code.code();
            this.rateLimited = code.kind() == KisErrorCode.Kind.RATE_LIMITED;
        }

        /** 초당 호출 한도 초과면 true(잠시 뒤 다시 부르면 된다). */
        public boolean rateLimited() {
            return rateLimited;
        }

        public String resultCode() {
            return code;
        }
    }

    private final HttpPort http;
    private final TokenCache tokens;
    private final BrokerCredentials credentials;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration interval;
    private final ObjectMapper mapper = new ObjectMapper();
    private Instant lastCall;

    KisRestClient(HttpPort http, TokenCache tokens, BrokerCredentials credentials, Clock clock, Sleeper sleeper,
            Duration interval) {
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    /** 실전 키로 조회 호출기를 만든다. 토큰은 처음 호출할 때 받는다(발급마다 알림톡이 가므로 미리 받지 않는다). */
    public static KisRestClient create(BrokerCredentials credentials, HttpPort http, Clock clock) {
        Objects.requireNonNull(credentials, "credentials");
        if (!credentials.isConfigured()) {
            throw new IllegalArgumentException("한국투자 키가 설정되지 않았습니다");
        }
        TokenCache tokens = new TokenCache(new KisTokenClient(http, credentials, clock), clock, Duration.ofHours(1));
        return new KisRestClient(http, tokens, credentials, clock, Thread::sleep, DEFAULT_INTERVAL);
    }

    /**
     * 조회 TR 하나를 부른다. 토큰 만료·무효 응답이면 토큰을 새로 받아 한 번만 다시 부른다(공식 legacy 샘플과 같은 방식).
     *
     * @param params 쿼리 입력값(예: {@code FID_INPUT_ISCD}). 순서는 이름순으로 고정한다
     */
    public synchronized JsonNode get(String path, String trId, Map<String, String> params)
            throws IOException, InterruptedException {
        Objects.requireNonNull(params, "params");
        requireReadOnly(path, trId);
        URI uri = BASE.resolve(path + query(params));
        HttpPort.Response r = send(uri, trId);
        JsonNode n = KisTokenClient.readOrNull(mapper, r.body());
        KisErrorCode code = n == null ? null : KisErrorCode.of(n);
        if (code != null && code.kind() == KisErrorCode.Kind.TOKEN_EXPIRED) {
            tokens.invalidate();
            r = send(uri, trId);
            n = KisTokenClient.readOrNull(mapper, r.body());
            code = n == null ? null : KisErrorCode.of(n);
        }
        if (code != null && !code.ok()) {
            throw new KisApiException(trId, code);
        }
        if (r.status() != 200 || n == null) {
            throw new IOException("한국투자 " + trId + " 실패: HTTP " + r.status());
        }
        return n;
    }

    static void requireReadOnly(String path, String trId) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(trId, "trId");
        String allowed = READ_ONLY.get(path);
        if (allowed == null) {
            throw new IllegalArgumentException("조회 전용 호출기가 허용하지 않는 경로: " + path);
        }
        if (!allowed.equals(trId)) {
            throw new IllegalArgumentException("조회 전용 호출기가 이 경로에 허용하지 않는 TR: " + trId);
        }
    }

    static String query(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringJoiner q = new StringJoiner("&", "?", "");
        new TreeMap<>(params).forEach((k, v) -> q.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return q.toString();
    }

    private HttpPort.Response send(URI uri, String trId) throws IOException, InterruptedException {
        pace();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json; charset=utf-8");
        headers.put("authorization", "Bearer " + tokens.get());
        headers.put("appkey", credentials.appKey());
        headers.put("appsecret", credentials.appSecret());
        headers.put("tr_id", trId);
        headers.put("custtype", "P");
        return http.get(uri, headers);
    }

    private void pace() throws InterruptedException {
        Instant now = clock.instant();
        if (lastCall != null) {
            Duration wait = Duration.between(now, lastCall.plus(interval));
            if (!wait.isNegative() && !wait.isZero()) {
                sleeper.sleep(wait);
                now = now.plus(wait);
            }
        }
        lastCall = now;
    }
}
