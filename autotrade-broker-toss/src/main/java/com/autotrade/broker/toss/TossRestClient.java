package com.autotrade.broker.toss;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.marketdata.live.CredentialsRejectedException;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * 토스 Open API REST 조회 호출기(읽기 전용, D-053). 시세·종목 정보·시장 정보·랭킹·시장 지표·섹터처럼
 * 계좌 없이 토큰만으로 부르는 GET만 보낸다.
 *
 * <p>안전 경계(안전 불변식 8, MD-01): 계좌({@code /api/v1/accounts}), 자산({@code holdings}), 주문·주문 내역
 * ({@code orders}), 조건주문({@code conditional-orders}), 주문 가능 정보({@code buying-power}, {@code sellable-quantity},
 * {@code commissions}) 경로는 허용 목록에 없어 보내기 전에 거절한다. 이 호출기에는 POST·DELETE 메서드가 없고
 * {@code X-Tossinvest-Account} 헤더를 넣는 코드도 없다. 허용 목록을 넓히는 변경은 risk-ops 검토 대상이다.
 *
 * <p>토큰(D-053, ADR-003 4장)
 * <ul>
 *   <li>`[사실]` 토스는 클라이언트당 유효 토큰이 1개이고, 다시 발급하면 이전 토큰이 즉시 무효다. 그래서 401
 *       {@code expired-token}·{@code invalid-token}이면 토큰을 버리고 한 번만 다시 부르지만, 401 {@code token-revoked}
 *       (다른 곳이 같은 키로 새 토큰을 받음)이면 바로 다시 받지 않는다. 다시 받으면 상대 프로세스(7단계 실전 엔진일 수
 *       있다)의 토큰을 끊고, 상대도 다시 받으면 서로 끊는 왕복이 된다. {@link #REVOKED_COOLDOWN} 동안 호출을 막고
 *       그 뒤 한 번만 다시 받는다.</li>
 *   <li>발급 실패 뒤 다음 발급은 원인과 상관없이 최소 60초 뒤다. 자격 증명 거절(400·401·403)이 3회 이어지면
 *       거부로 보고 간격을 2분부터 두 배씩 늘려 30분에서 멈춘다(ADR-003 4장과 같은 규칙).</li>
 * </ul>
 */
public final class TossRestClient {

    static final URI BASE = URI.create("https://openapi.tossinvest.com");
    static final Duration AUTH_RETRY_FLOOR = Duration.ofSeconds(60);
    static final Duration REJECTED_BACKOFF_START = Duration.ofMinutes(2);
    static final Duration REJECTED_BACKOFF_MAX = Duration.ofMinutes(30);
    static final int REJECTIONS_BEFORE_DEGRADED = 3;
    static final Duration REVOKED_COOLDOWN = Duration.ofMinutes(10);
    /** 토큰 만료 전 재발급 여유. 토큰 수명은 명세 예시 기준 24시간이다. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(10);

    /** 경로 한 마디(명세의 심볼 형식). 점만으로 된 마디({@code ..})는 경로를 거슬러 올라가므로 막는다. */
    private static final String SEG = "(?!\\.+(?:/|$))[A-Za-z0-9.\\-]+";

    /** 허용 경로 → Rate Limits Group(`[사실]` 명세의 엔드포인트별 그룹). */
    private static final List<Map.Entry<Pattern, String>> READ_ONLY_PATHS = List.of(
            route("/api/v1/orderbook", "MARKET_DATA"),
            route("/api/v1/prices", "MARKET_DATA"),
            route("/api/v1/trades", "MARKET_DATA"),
            route("/api/v1/price-limits", "MARKET_DATA"),
            route("/api/v1/candles", "MARKET_DATA_CHART"),
            route("/api/v1/stocks", "STOCK"),
            route("/api/v1/stocks/all", "STOCK_ALL"),
            route("/api/v1/stocks/" + SEG + "/warnings", "STOCK"),
            route("/api/v1/stocks/" + SEG
                    + "/(investor-trading|program-trades|short-selling|credit-trades|securities-lending)",
                    "STOCK_TRADING_TREND"),
            route("/api/v1/exchange-rate", "MARKET_INFO"),
            route("/api/v1/market-calendar/(KR|US)", "MARKET_INFO"),
            route("/api/v1/rankings", "RANKING"),
            route("/api/v1/market-indicators/prices", "MARKET_INDICATOR"),
            route("/api/v1/market-indicators/" + SEG + "/candles", "MARKET_INDICATOR_CHART"),
            route("/api/v1/market-indicators/" + SEG + "/investor-trading", "MARKET_INDICATOR"),
            route("/api/v1/sectors", "SECTOR"),
            route("/api/v1/sectors/rankings", "SECTOR_RANKING"),
            route("/api/v1/sectors/" + SEG, "SECTOR"),
            route("/api/v1/sectors/" + SEG + "/(stocks|etfs)", "SECTOR"));

    /** 이 호출기 상태(플러그인 상태 판단용). */
    public enum Health {
        /** 아직 성공한 호출이 없다. */
        UNKNOWN,
        /** 마지막 토큰 발급·호출이 인증을 통과했다. */
        OK,
        /** 자격 증명 거절이 이어졌거나 토큰이 다른 곳에서 재발급돼 막혀 있다. */
        DEGRADED
    }

    private final HttpPort http;
    private final TokenCache tokens;
    private final Clock clock;
    private final TossRateLimiter limiter;
    private final ObjectMapper mapper = new ObjectMapper();

    private Instant nextAuthAllowed = Instant.MIN;
    private int consecutiveRejections;
    private IOException lastAuthFailure;
    private Instant revokedUntil;
    private volatile Health health = Health.UNKNOWN;

    TossRestClient(HttpPort http, TokenCache.Fetcher fetcher, Clock clock, TossRateLimiter limiter) {
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.tokens = new TokenCache(guarded(Objects.requireNonNull(fetcher, "fetcher")), clock, REFRESH_MARGIN);
    }

    /** 실전 키로 조회 호출기를 만든다. 토큰은 처음 호출할 때 받는다. */
    public static TossRestClient create(BrokerCredentials credentials, HttpPort http, Clock clock) {
        Objects.requireNonNull(credentials, "credentials");
        if (!credentials.isConfigured()) {
            throw new IllegalArgumentException("토스 키가 설정되지 않았습니다");
        }
        TossRateLimiter limiter = new TossRateLimiter(clock, Thread::sleep);
        return new TossRestClient(http, new TossTokenClient(http, credentials, clock, limiter), clock, limiter);
    }

    /**
     * 허용된 조회 경로에 GET을 보내고 응답 {@code result}를 돌려준다. 쿼리 값은 UTF-8로 인코딩한다
     * (캔들 {@code before}의 {@code +}는 {@code %2B}가 된다). 다건 조회 구분자 {@code ,}는 명세 예시대로 그대로 둔다.
     */
    public synchronized JsonNode get(String path, Map<String, String> query) throws IOException, InterruptedException {
        Objects.requireNonNull(query, "query");
        String group = requireReadOnly(path);
        if (revokedUntil != null) {
            if (clock.instant().isBefore(revokedUntil)) {
                throw new TossApiException("조회", TossApiException.Kind.TOKEN_REVOKED, 401, "token-revoked", "", null);
            }
            revokedUntil = null; // 쉬는 시간이 끝났다. 토큰은 이미 버렸으므로 이번 호출이 한 번 새로 받는다
        }
        URI uri = BASE.resolve(path + queryString(query));
        HttpPort.Response r = send(group, uri);
        if (r.status() == 401 && kindOf(r) == TossApiException.Kind.TOKEN_INVALID) {
            tokens.invalidate();
            r = send(group, uri);
        }
        if (r.status() == 200) {
            JsonNode n = parse(r);
            if (n == null || !n.has("result")) {
                throw new TossApiException("조회", TossApiException.Kind.OTHER, 200, "", requestId(r, n), null);
            }
            health = Health.OK;
            return n.get("result");
        }
        throw failure(r);
    }

    /** 플러그인 상태 판단용. 블로킹하지 않는다. */
    public Health health() {
        return health;
    }

    static String requireReadOnly(String path) {
        Objects.requireNonNull(path, "path");
        for (Map.Entry<Pattern, String> e : READ_ONLY_PATHS) {
            if (e.getKey().matcher(path).matches()) {
                return e.getValue();
            }
        }
        throw new IllegalArgumentException("조회 전용 호출기가 허용하지 않는 경로: " + path);
    }

    private HttpPort.Response send(String group, URI uri) throws IOException, InterruptedException {
        String token = tokens.get();
        limiter.acquire(group);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);
        HttpPort.Response r = http.get(uri, headers);
        limiter.onResponse(group, r);
        return r;
    }

    private TossApiException failure(HttpPort.Response r) {
        JsonNode n = parse(r);
        String code = n == null ? "" : n.path("error").path("code").asText("");
        TossApiException.Kind kind = TossApiException.classify(r.status(), code);
        if (kind == TossApiException.Kind.TOKEN_REVOKED) {
            tokens.invalidate();
            revokedUntil = clock.instant().plus(REVOKED_COOLDOWN);
            health = Health.DEGRADED;
        }
        return new TossApiException("조회", kind, r.status(), code, requestId(r, n),
                kind == TossApiException.Kind.RATE_LIMITED ? TossRateLimiter.retryAfter(r) : null);
    }

    private TossApiException.Kind kindOf(HttpPort.Response r) {
        JsonNode n = parse(r);
        return TossApiException.classify(r.status(), n == null ? "" : n.path("error").path("code").asText(""));
    }

    /** 발급 간격 하한과 거부 백오프를 거는 발급기. {@link #get}이 잡은 잠금 안에서만 불린다. */
    private TokenCache.Fetcher guarded(TokenCache.Fetcher fetcher) {
        return () -> {
            Instant now = clock.instant();
            if (now.isBefore(nextAuthAllowed) && lastAuthFailure != null) {
                throw lastAuthFailure instanceof CredentialsRejectedException
                        ? new CredentialsRejectedException("토스 토큰 발급 대기 중: 직전 발급이 거절됨")
                        : new IOException("토스 토큰 발급 대기 중: 직전 발급 실패");
            }
            try {
                TokenCache.Issued issued = fetcher.fetch();
                consecutiveRejections = 0;
                lastAuthFailure = null;
                nextAuthAllowed = Instant.MIN;
                return issued;
            } catch (CredentialsRejectedException e) {
                consecutiveRejections++;
                lastAuthFailure = e;
                nextAuthAllowed = now.plus(rejectedWait(consecutiveRejections));
                if (consecutiveRejections >= REJECTIONS_BEFORE_DEGRADED) {
                    health = Health.DEGRADED;
                }
                throw e;
            } catch (IOException e) {
                lastAuthFailure = e;
                nextAuthAllowed = now.plus(AUTH_RETRY_FLOOR);
                throw e;
            }
        };
    }

    static Duration rejectedWait(int consecutive) {
        if (consecutive < REJECTIONS_BEFORE_DEGRADED) {
            return AUTH_RETRY_FLOOR;
        }
        Duration d = REJECTED_BACKOFF_START;
        for (int i = REJECTIONS_BEFORE_DEGRADED; i < consecutive && d.compareTo(REJECTED_BACKOFF_MAX) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(REJECTED_BACKOFF_MAX) > 0 ? REJECTED_BACKOFF_MAX : d;
    }

    private JsonNode parse(HttpPort.Response r) {
        try {
            JsonNode n = mapper.readTree(r.body());
            return n != null && n.isObject() ? n : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String requestId(HttpPort.Response r, JsonNode n) {
        String fromBody = n == null ? "" : n.path("error").path("requestId").asText("");
        return fromBody.isEmpty() ? r.headers().getOrDefault("x-request-id", "") : fromBody;
    }

    private static String queryString(Map<String, String> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringJoiner j = new StringJoiner("&", "?", "");
        query.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(Objects.requireNonNull(v, k), StandardCharsets.UTF_8).replace("%2C", ",")));
        return j.toString();
    }

    private static Map.Entry<Pattern, String> route(String regex, String group) {
        return Map.entry(Pattern.compile(regex), group);
    }
}
