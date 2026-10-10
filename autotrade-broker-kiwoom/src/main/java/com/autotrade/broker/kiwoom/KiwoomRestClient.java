package com.autotrade.broker.kiwoom;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 키움 REST 조회 TR 호출기(읽기 전용). 시세·종목정보·차트·순위처럼 계좌와 상관없는 조회만 부른다.
 *
 * <p>`[사실]` 근거: 사용자 제공 키움 REST API 명세 JSON(2026-10-10 수신, 요약 docs/research/kiwoom-rest-api-spec.md 2장)
 * <ul>
 *   <li>모든 조회 TR은 {@code POST https://api.kiwoom.com<url>}, 본문 JSON, 헤더 {@code api-id}(TR 코드),
 *       {@code authorization: Bearer <토큰>}.</li>
 *   <li>연속조회: 응답 헤더 {@code cont-yn}이 Y면 다음 요청에 {@code cont-yn: Y}와 응답 헤더 {@code next-key}를 넣는다.</li>
 *   <li>한도: 국내 조회 TR은 계좌(토큰)별 초당 5회(docs/research/market-data-limits.md K1). 초과 시 1700·1701·1702.</li>
 * </ul>
 *
 * <p>안전 경계(안전 불변식 8, MD-01): 주문({@code /api/dostk/ordr}, {@code crdordr}, {@code /api/us/ordr}),
 * 계좌({@code acnt}), 환전({@code /api/us/exchange}), 관심종목({@code watchlist}) 경로와 {@code kt}·{@code ust} TR은
 * 부르기 전에 거절한다. 허용 목록 밖의 경로를 열려면 이 클래스를 고쳐야 하고, 그런 변경은 risk-ops 검토 대상이다.
 *
 * <p>같은 키로 토큰을 새로 받으면 실시간 세션(토큰당 1개)에 영향이 있을 수 있다(Q-015 미확인). 그래서 이 호출기는
 * 게이트웨이 실시간 수신과 같은 시간에 따로 띄우지 않는다(호출하는 도구가 안내한다).
 */
public final class KiwoomRestClient {

    static final URI BASE = URI.create("https://api.kiwoom.com");
    /** 토큰별 초당 5회 한도에 여유를 둔 호출 간격 `[추론]`. */
    static final Duration DEFAULT_INTERVAL = Duration.ofMillis(250);

    /** 조회 전용으로 허용하는 경로(명세 url 그룹). */
    static final Set<String> READ_ONLY_PATHS = Set.of(
            "/api/dostk/stkinfo", "/api/dostk/mrkcond", "/api/dostk/chart", "/api/dostk/sect", "/api/dostk/rkinfo",
            "/api/dostk/frgnistt", "/api/dostk/elw", "/api/dostk/etf", "/api/dostk/thme", "/api/dostk/shsa",
            "/api/dostk/slb",
            "/api/us/stkinfo", "/api/us/mrkcond", "/api/us/chart", "/api/us/sect", "/api/us/rkinfo", "/api/us/invtinfo");

    /** 호출 간격 대기. 운영은 {@link Thread#sleep}, 테스트는 기록만 한다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    /** 응답 한 쪽. 본문에는 토큰이 없지만 계약상 로그에 통째로 남기지 않는다. */
    public record Page(JsonNode body, Optional<String> nextKey) {
        public Page {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(nextKey, "nextKey");
        }
    }

    /** 키움이 오류 코드로 답했다. 메시지에는 코드와 고정 안내만 들어간다. */
    public static final class KiwoomApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient KiwoomReturnCode code;

        KiwoomApiException(String apiId, KiwoomReturnCode code) {
            super("키움 " + apiId + " 실패: " + code.describe());
            this.code = code;
        }

        /** 호출 한도 초과면 true(잠시 뒤 다시 부르면 된다). */
        public boolean rateLimited() {
            return code.kind() == KiwoomReturnCode.Kind.RATE_LIMITED;
        }

        public int returnCode() {
            return code.specific();
        }
    }

    private final HttpPort http;
    private final TokenCache tokens;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration interval;
    private final ObjectMapper mapper = new ObjectMapper();
    private Instant lastCall;

    KiwoomRestClient(HttpPort http, TokenCache tokens, Clock clock, Sleeper sleeper, Duration interval) {
        this.http = Objects.requireNonNull(http, "http");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    /** 실전 키로 조회 호출기를 만든다. 토큰은 처음 호출할 때 받는다. */
    public static KiwoomRestClient create(BrokerCredentials credentials, HttpPort http, Clock clock) {
        Objects.requireNonNull(credentials, "credentials");
        if (!credentials.isConfigured()) {
            throw new IllegalArgumentException("키움 키가 설정되지 않았습니다");
        }
        TokenCache tokens = new TokenCache(new KiwoomTokenClient(http, credentials), clock, Duration.ofMinutes(10));
        return new KiwoomRestClient(http, tokens, clock, Thread::sleep, DEFAULT_INTERVAL);
    }

    /** 조회 TR 한 쪽을 부른다. {@code nextKey}가 있으면 연속조회로 보낸다. */
    public synchronized Page call(String apiId, String path, ObjectNode body, Optional<String> nextKey)
            throws IOException, InterruptedException {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(nextKey, "nextKey");
        requireReadOnly(apiId, path);
        String json = mapper.writeValueAsString(body);
        HttpPort.Response r = send(apiId, path, json, nextKey);
        JsonNode n = parse(r);
        KiwoomReturnCode code = n == null ? null : KiwoomReturnCode.of(n);
        if (code != null && code.kind() == KiwoomReturnCode.Kind.TOKEN_EXPIRED) {
            tokens.invalidate(); // 만료·폐기된 토큰은 새로 받아 한 번만 다시 부른다(공식 저장소와 같은 방식)
            r = send(apiId, path, json, nextKey);
            n = parse(r);
            code = n == null ? null : KiwoomReturnCode.of(n);
        }
        if (code != null && !code.ok()) {
            throw new KiwoomApiException(apiId, code);
        }
        if (r.status() != 200 || n == null) {
            throw new IOException("키움 " + apiId + " 실패: HTTP " + r.status());
        }
        boolean more = "Y".equalsIgnoreCase(r.headers().getOrDefault("cont-yn", "").strip());
        String key = r.headers().getOrDefault("next-key", "").strip();
        return new Page(n, more && !key.isEmpty() ? Optional.of(key) : Optional.empty());
    }

    /** 연속조회를 {@code maxPages}쪽까지 이어 부른다. */
    public List<JsonNode> callAll(String apiId, String path, ObjectNode body, int maxPages)
            throws IOException, InterruptedException {
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages < 1");
        }
        List<JsonNode> out = new ArrayList<>();
        Optional<String> next = Optional.empty();
        do {
            Page p = call(apiId, path, body, next);
            out.add(p.body());
            next = p.nextKey();
        } while (next.isPresent() && out.size() < maxPages);
        return out;
    }

    /** 요청 본문용 빈 JSON 객체. */
    public ObjectNode newBody() {
        return mapper.createObjectNode();
    }

    static void requireReadOnly(String apiId, String path) {
        Objects.requireNonNull(apiId, "apiId");
        Objects.requireNonNull(path, "path");
        if (!READ_ONLY_PATHS.contains(path)) {
            throw new IllegalArgumentException("조회 전용 호출기가 허용하지 않는 경로: " + path);
        }
        if (!apiId.matches("(ka|usa)\\d{5}")) {
            throw new IllegalArgumentException("조회 전용 호출기가 허용하지 않는 TR: " + apiId);
        }
    }

    private HttpPort.Response send(String apiId, String path, String json, Optional<String> nextKey)
            throws IOException, InterruptedException {
        pace();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("api-id", apiId);
        headers.put("authorization", "Bearer " + tokens.get());
        nextKey.ifPresent(k -> {
            headers.put("cont-yn", "Y");
            headers.put("next-key", k);
        });
        return http.post(BASE.resolve(path), headers, json);
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

    private JsonNode parse(HttpPort.Response r) {
        try {
            JsonNode n = mapper.readTree(r.body());
            return n != null && n.isObject() ? n : null;
        } catch (IOException e) {
            return null;
        }
    }
}
