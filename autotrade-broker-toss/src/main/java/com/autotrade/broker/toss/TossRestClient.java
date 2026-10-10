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

    /**
     * 경로 변수 형식(`[사실]` 명세의 path 파라미터 pattern). 종목 심볼은 점을 허용하므로 점만으로 된 마디({@code ..})는
     * 경로를 거슬러 올라가지 않게 따로 막는다.
     */
    private static final String SYMBOL = "(?!\\.+(?:/|$))[A-Za-z0-9.\\-]+";
    /** 시장 지표 심볼({@code KOSPI}, {@code KR_BOND_3Y} 등): {@code ^[A-Za-z0-9_]+$}. */
    private static final String INDICATOR = "[A-Za-z0-9_]+";
    /** 섹터 ID: {@code ^[A-Za-z0-9_-]{1,40}$}. */
    private static final String SECTOR_ID = "[A-Za-z0-9_\\-]{1,40}";

    /** 허용 경로 → Rate Limits Group(`[사실]` 명세의 엔드포인트별 그룹). */
    private static final List<Map.Entry<Pattern, String>> READ_ONLY_PATHS = List.of(
            route("/api/v1/orderbook", "MARKET_DATA"),
            route("/api/v1/prices", "MARKET_DATA"),
            route("/api/v1/trades", "MARKET_DATA"),
            route("/api/v1/price-limits", "MARKET_DATA"),
            route("/api/v1/candles", "MARKET_DATA_CHART"),
            route("/api/v1/stocks", "STOCK"),
            route("/api/v1/stocks/all", "STOCK_ALL"),
            route("/api/v1/stocks/" + SYMBOL + "/warnings", "STOCK"),
            route("/api/v1/stocks/" + SYMBOL
                    + "/(investor-trading|program-trades|short-selling|credit-trades|securities-lending)",
                    "STOCK_TRADING_TREND"),
            route("/api/v1/exchange-rate", "MARKET_INFO"),
            route("/api/v1/market-calendar/(KR|US)", "MARKET_INFO"),
            route("/api/v1/rankings", "RANKING"),
            route("/api/v1/market-indicators/prices", "MARKET_INDICATOR"),
            route("/api/v1/market-indicators/" + INDICATOR + "/candles", "MARKET_INDICATOR_CHART"),
            route("/api/v1/market-indicators/" + INDICATOR + "/investor-trading", "MARKET_INDICATOR"),
            route("/api/v1/sectors", "SECTOR"),
            route("/api/v1/sectors/rankings", "SECTOR_RANKING"),
            route("/api/v1/sectors/" + SECTOR_ID, "SECTOR"),
            route("/api/v1/sectors/" + SECTOR_ID + "/(stocks|etfs)", "SECTOR"));

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

    /** 아래 상태 필드 보호용. HTTP 호출과 한도 대기는 이 잠금 밖에서 한다(PR #41 architect F2). */
    private final Object lock = new Object();
    private Instant nextAuthAllowed = Instant.MIN;
    private int consecutiveRejections;
    private IOException lastAuthFailure;
    private Instant revokedUntil;
    private volatile Health health = Health.UNKNOWN;
    /**
     * 토큰 꺼내기·발급·버리기를 한 줄로 세우는 잠금. {@link TokenCache} 내부 동기화에 기대지 않으려고 따로 둔다
     * (PR #41 architect N10). 잠금 순서는 tokenLock → TokenCache → lock 이다.
     */
    private final Object tokenLock = new Object();
    /** 이 호출기가 마지막으로 받은 토큰 값. {@link #tokenLock} 안에서만 바뀐다. 로그·예외에 넣지 않는다. */
    private volatile String currentToken;

    /** 401을 받은 요청의 토큰이 지금 어떤 상태인지. */
    private enum SentTokenState {
        /** 아직 캐시의 토큰이었고 방금 버렸다. */
        DROPPED,
        /** 이 호출기가 이미 새 토큰으로 바꿨다. */
        REPLACED,
        /** 다른 요청이 이미 버렸고 새 토큰은 아직 없다. */
        EMPTY
    }

    /** 보낸 요청의 응답과 그 요청에 실은 토큰. */
    private record Sent(HttpPort.Response response, String token) {}

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
    public JsonNode get(String path, Map<String, String> query) throws IOException, InterruptedException {
        return get(path, query, null);
    }

    /**
     * {@link #get(String, Map)}와 같되, 그룹 호출 한도 때문에 {@code maxWait}보다 오래 기다려야 하면 요청을 보내지 않고
     * {@link TossApiException.Kind#RATE_LIMITED}로 바로 실패한다(보호 폴링용, PR #41 risk-ops F1).
     */
    public JsonNode get(String path, Map<String, String> query, Duration maxWait)
            throws IOException, InterruptedException {
        Objects.requireNonNull(query, "query");
        String group = requireReadOnly(path);
        synchronized (lock) {
            if (revokedUntil != null) {
                if (clock.instant().isBefore(revokedUntil)) {
                    throw new TossApiException("조회", TossApiException.Kind.TOKEN_REVOKED, 401, "token-revoked", "",
                            null);
                }
                revokedUntil = null; // 쉬는 시간이 끝났다. 토큰은 이미 버렸으므로 이번 호출이 한 번 새로 받는다
            }
        }
        URI uri = BASE.resolve(path + queryString(query));
        Sent sent = send(group, uri, maxWait);
        if (sent.response().status() == 401) {
            TossApiException.Kind kind = kindOf(sent.response());
            if (kind == TossApiException.Kind.TOKEN_INVALID || kind == TossApiException.Kind.TOKEN_REVOKED) {
                switch (dropIfCurrent(sent.token())) {
                    // 이 요청이 나간 사이 이 호출기가 이미 새 토큰을 받았다. 이전 토큰이 끊긴 것은 우리 재발급 때문이므로
                    // 남의 재발급으로 보지 않고 새 토큰으로 한 번만 다시 보낸다(PR #41 architect F4).
                    case REPLACED -> sent = send(group, uri, maxWait);
                    case DROPPED -> {
                        if (kind == TossApiException.Kind.TOKEN_REVOKED) {
                            startRevokedCooldown();
                            throw error(sent.response());
                        }
                        sent = send(group, uri, maxWait);
                    }
                    case EMPTY -> {
                        // 다른 요청이 같은 토큰을 이미 버렸다. 그 요청이 쉬는 시간을 걸었으면 다시 보내지 않는다(F5).
                        // 아니면(만료로 버린 경우) 한 번 다시 보낸다. 쉬는 중의 발급은 발급기가 막는다.
                        if (inRevokedCooldown()) {
                            throw error(sent.response());
                        }
                        sent = send(group, uri, maxWait);
                    }
                }
            }
        }
        HttpPort.Response r = sent.response();
        if (r.status() == 200) {
            JsonNode n = parse(r);
            if (n == null || !n.has("result")) {
                throw new TossApiException("조회", TossApiException.Kind.OTHER, 200, "", requestId(r, n), null);
            }
            health = Health.OK;
            return n.get("result");
        }
        throw failure(sent);
    }

    /** 플러그인 상태 판단용. 블로킹하지 않는다. */
    public Health health() {
        return health;
    }

    /**
     * 수명이 짧은 토큰(재발급 여유 10분의 두 배 미만)은 캐시가 호출마다 다시 받지 않게, 수명의 절반이 지난 뒤에 다시
     * 받도록 캐시에 넘기는 만료 시각을 늦춘다. 토스는 새 토큰이 이전 토큰을 바로 끊으므로 재발급은 꼭 필요할 때만
     * 한다(PR #41 risk-ops F2). 캐시에 넘기는 만료 시각은 실제보다 늦을 수 있지만, 캐시가 다시 받는
     * 시점({@code 발급 + 수명/2})은 실제 만료보다 앞선다. 실제 토큰 값은 바꾸지 않는다.
     */
    static TokenCache.Issued withRefreshAtHalfLife(TokenCache.Issued issued, Instant issuedAt) {
        Duration life = Duration.between(issuedAt, issued.expiresAt());
        if (life.compareTo(REFRESH_MARGIN.multipliedBy(2)) >= 0) {
            return issued;
        }
        Duration half = life.isNegative() ? Duration.ZERO : life.dividedBy(2);
        return new TokenCache.Issued(issued.value(), issuedAt.plus(half).plus(REFRESH_MARGIN));
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

    private Sent send(String group, URI uri, Duration maxWait) throws IOException, InterruptedException {
        String token;
        synchronized (tokenLock) {
            token = tokens.get();
        }
        limiter.acquire(group, maxWait);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);
        HttpPort.Response r = http.get(uri, headers);
        limiter.onResponse(group, r);
        return new Sent(r, token);
    }

    /**
     * 보낸 토큰이 아직 캐시의 토큰이면 버리고 {@code DROPPED}. 이미 새 토큰이면 {@code REPLACED}, 이미 비었으면
     * {@code EMPTY}. 확인과 버리기를 {@link #tokenLock} 안에서 해 그 사이 받은 새 토큰을 버리지 않는다(발급도 그 잠금 안).
     */
    private SentTokenState dropIfCurrent(String sentToken) {
        synchronized (tokenLock) {
            if (currentToken == null) {
                return SentTokenState.EMPTY;
            }
            if (!sentToken.equals(currentToken)) {
                return SentTokenState.REPLACED;
            }
            tokens.invalidate();
            currentToken = null;
            return SentTokenState.DROPPED;
        }
    }

    private boolean inRevokedCooldown() {
        synchronized (lock) {
            return revokedUntil != null && clock.instant().isBefore(revokedUntil);
        }
    }

    /** 다른 곳이 같은 Client ID로 재발급해 우리 토큰이 끊겼다. 10분 쉬고 한 번 다시 받는다. */
    private void startRevokedCooldown() {
        synchronized (lock) {
            revokedUntil = clock.instant().plus(REVOKED_COOLDOWN);
        }
        health = Health.DEGRADED;
    }

    private TossApiException failure(Sent sent) {
        HttpPort.Response r = sent.response();
        if (r.status() == 401 && kindOf(r) == TossApiException.Kind.TOKEN_REVOKED
                && dropIfCurrent(sent.token()) == SentTokenState.DROPPED) {
            startRevokedCooldown(); // 새 토큰으로 다시 보낸 요청도 끊겼다: 이번에는 남의 재발급이다
        }
        return error(r);
    }

    private TossApiException error(HttpPort.Response r) {
        JsonNode n = parse(r);
        String code = n == null ? "" : n.path("error").path("code").asText("");
        TossApiException.Kind kind = TossApiException.classify(r.status(), code);
        return new TossApiException("조회", kind, r.status(), code, requestId(r, n),
                kind == TossApiException.Kind.RATE_LIMITED ? TossRateLimiter.retryAfter(r) : null);
    }

    private TossApiException.Kind kindOf(HttpPort.Response r) {
        JsonNode n = parse(r);
        return TossApiException.classify(r.status(), n == null ? "" : n.path("error").path("code").asText(""));
    }

    /**
     * 발급 간격 하한과 거부 백오프를 거는 발급기. {@link TokenCache}의 잠금 안에서 불려 한 번에 하나만 발급한다.
     * 토큰 발급이 429여도 다음 발급은 {@code Retry-After}가 아니라 60초 하한을 따른다(ADR-003 4장: 발급 실패는 원인과
     * 상관없이 60초 뒤. 발급 그룹 한도가 초당 5회라 60초면 충분히 풀린다).
     */
    private TokenCache.Fetcher guarded(TokenCache.Fetcher fetcher) {
        return () -> {
            Instant now = clock.instant();
            synchronized (lock) {
                if (revokedUntil != null && now.isBefore(revokedUntil)) {
                    // 남의 재발급으로 끊겨 쉬는 중에는 어떤 경로로도 새로 받지 않는다(맞재발급 방지, PR #41 architect F5)
                    throw new TossApiException("발급", TossApiException.Kind.TOKEN_REVOKED, 401, "token-revoked", "",
                            null);
                }
                if (now.isBefore(nextAuthAllowed) && lastAuthFailure != null) {
                    throw lastAuthFailure instanceof CredentialsRejectedException
                            ? new CredentialsRejectedException("토스 토큰 발급 대기 중: 직전 발급이 거절됨")
                            : new IOException("토스 토큰 발급 대기 중: 직전 발급 실패");
                }
            }
            try {
                TokenCache.Issued issued = fetcher.fetch();
                synchronized (lock) {
                    consecutiveRejections = 0;
                    lastAuthFailure = null;
                    nextAuthAllowed = Instant.MIN;
                }
                currentToken = issued.value(); // tokenLock 안이다(send가 잡고 tokens.get을 부른다)
                return withRefreshAtHalfLife(issued, clock.instant());
            } catch (CredentialsRejectedException e) {
                synchronized (lock) {
                    consecutiveRejections++;
                    lastAuthFailure = e;
                    nextAuthAllowed = now.plus(rejectedWait(consecutiveRejections));
                    if (consecutiveRejections >= REJECTIONS_BEFORE_DEGRADED) {
                        health = Health.DEGRADED;
                    }
                }
                throw e;
            } catch (IOException e) {
                synchronized (lock) {
                    lastAuthFailure = e;
                    nextAuthAllowed = now.plus(AUTH_RETRY_FLOOR);
                }
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
