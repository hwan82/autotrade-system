package com.autotrade.broker.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.marketdata.live.CredentialsRejectedException;
import com.autotrade.marketdata.live.TokenCache;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TossRestClientTest {

    private static final Instant T0 = Instant.parse("2026-10-10T00:00:00Z");
    private static final String PRICES = "{\"result\":[{\"symbol\":\"005930\",\"timestamp\":\"2026-10-10T09:00:01+09:00\","
            + "\"lastPrice\":\"71000\",\"currency\":\"KRW\"}]}";

    private final MutableClock clock = new MutableClock(T0);
    private final FakeHttp http = new FakeHttp();
    /** 발급기 결과 대기열. 비면 순번 토큰을 낸다. */
    private final Deque<Object> issue = new ArrayDeque<>();
    private int fetches;
    private final TokenCache.Fetcher fetcher = () -> {
        fetches++;
        Object next = issue.pollFirst();
        if (next instanceof IOException e) {
            throw e;
        }
        return new TokenCache.Issued("tok-" + fetches, clock.instant().plus(Duration.ofDays(1)));
    };
    private final TossRestClient client =
            new TossRestClient(http, fetcher, clock, new TossRateLimiter(clock, clock::advance));

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/accounts", "/api/v1/holdings", "/api/v1/orders", "/api/v1/orders/abc",
        "/api/v1/orders/abc/cancel", "/api/v1/conditional-orders", "/api/v1/buying-power",
        "/api/v1/sellable-quantity", "/api/v1/commissions", "/oauth2/token", "/api/v1/sectors/..",
        "/api/v1/stocks/../orders", "/api/v1/prices/", "/api/v2/prices", "api/v1/prices"})
    void 계좌_주문_경로와_허용_목록_밖은_보내기_전에_거절한다(String path) {
        assertThatThrownBy(() -> client.get(path, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(http.calls).isEmpty();
        assertThat(fetches).isZero();
    }

    @Test
    void 명세의_조회_경로는_해당_Rate_Limits_Group으로_허용한다() {
        assertThat(TossRestClient.requireReadOnly("/api/v1/prices")).isEqualTo("MARKET_DATA");
        assertThat(TossRestClient.requireReadOnly("/api/v1/candles")).isEqualTo("MARKET_DATA_CHART");
        assertThat(TossRestClient.requireReadOnly("/api/v1/stocks/all")).isEqualTo("STOCK_ALL");
        assertThat(TossRestClient.requireReadOnly("/api/v1/stocks/005930/warnings")).isEqualTo("STOCK");
        assertThat(TossRestClient.requireReadOnly("/api/v1/stocks/0101N0/short-selling"))
                .isEqualTo("STOCK_TRADING_TREND");
        assertThat(TossRestClient.requireReadOnly("/api/v1/market-calendar/US")).isEqualTo("MARKET_INFO");
        assertThat(TossRestClient.requireReadOnly("/api/v1/market-indicators/KOSPI/candles"))
                .isEqualTo("MARKET_INDICATOR_CHART");
        assertThat(TossRestClient.requireReadOnly("/api/v1/sectors/rankings")).isEqualTo("SECTOR_RANKING");
        assertThat(TossRestClient.requireReadOnly("/api/v1/sectors/S123/etfs")).isEqualTo("SECTOR");
    }

    /** 명세의 GET 조회 경로 전부와 경로 변수 견본(밑줄·하이픈·점 포함). PR #41 architect F1. */
    @ParameterizedTest
    @CsvSource({
        "/api/v1/orderbook, MARKET_DATA",
        "/api/v1/prices, MARKET_DATA",
        "/api/v1/trades, MARKET_DATA",
        "/api/v1/price-limits, MARKET_DATA",
        "/api/v1/candles, MARKET_DATA_CHART",
        "/api/v1/stocks, STOCK",
        "/api/v1/stocks/all, STOCK_ALL",
        "/api/v1/stocks/005930/warnings, STOCK",
        "/api/v1/stocks/BRK.B/warnings, STOCK",
        "/api/v1/stocks/0101N0/investor-trading, STOCK_TRADING_TREND",
        "/api/v1/stocks/005930/program-trades, STOCK_TRADING_TREND",
        "/api/v1/stocks/005930/short-selling, STOCK_TRADING_TREND",
        "/api/v1/stocks/005930/credit-trades, STOCK_TRADING_TREND",
        "/api/v1/stocks/BF-B/securities-lending, STOCK_TRADING_TREND",
        "/api/v1/exchange-rate, MARKET_INFO",
        "/api/v1/market-calendar/KR, MARKET_INFO",
        "/api/v1/market-calendar/US, MARKET_INFO",
        "/api/v1/rankings, RANKING",
        "/api/v1/market-indicators/prices, MARKET_INDICATOR",
        "/api/v1/market-indicators/KOSPI/candles, MARKET_INDICATOR_CHART",
        "/api/v1/market-indicators/KR_BOND_3Y/candles, MARKET_INDICATOR_CHART",
        "/api/v1/market-indicators/USD_KRW/investor-trading, MARKET_INDICATOR",
        "/api/v1/sectors, SECTOR",
        "/api/v1/sectors/rankings, SECTOR_RANKING",
        "/api/v1/sectors/KR_SEMI-01, SECTOR",
        "/api/v1/sectors/KR_SEMI-01/stocks, SECTOR",
        "/api/v1/sectors/S123/etfs, SECTOR"})
    void 명세의_GET_조회_경로_표(String path, String group) {
        assertThat(TossRestClient.requireReadOnly(path)).isEqualTo(group);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/market-indicators/../candles", "/api/v1/market-indicators/KR.BOND/candles",
        "/api/v1/sectors/../stocks", "/api/v1/sectors/a.b", "/api/v1/stocks/./warnings",
        "/api/v1/market-calendar/JP", "/api/v1/sectors/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void 경로_변수_형식이_틀리면_거절한다(String path) {
        assertThatThrownBy(() -> TossRestClient.requireReadOnly(path)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다른_그룹의_한도_대기는_현재가_조회를_막지_않고_같은_그룹이_오래_막히면_보내지_않고_실패한다() throws Exception {
        http.respond(429, "{\"error\":{\"code\":\"rate-limited\"}}", Map.of("retry-after", "3"));
        assertThatThrownBy(() -> client.get("/api/v1/rankings", Map.of())).isInstanceOf(TossApiException.class);
        http.respond(200, PRICES);

        Instant before = clock.instant();
        client.get("/api/v1/prices", Map.of("symbols", "005930"), Duration.ofSeconds(1));
        assertThat(clock.instant()).isEqualTo(before); // 랭킹 그룹의 3초 대기를 기다리지 않았다

        http.respond(429, "{\"error\":{\"code\":\"rate-limited\"}}", Map.of("retry-after", "3"));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930"), Duration.ofSeconds(1)))
                .isInstanceOf(TossApiException.class);
        int sent = http.calls.size();
        Instant after429 = clock.instant();
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930"), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(TossApiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(TossApiException.Kind.RATE_LIMITED);
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(3));
                });
        assertThat(http.calls).hasSize(sent); // 요청을 보내지 않았다
        assertThat(clock.instant()).isEqualTo(after429); // 기다리지도 않았다
    }

    @Test
    void 수명이_짧은_토큰은_호출마다_다시_받지_않고_수명의_절반이_지나야_다시_받는다() throws Exception {
        TokenCache.Fetcher shortLived = () -> {
            fetches++;
            return new TokenCache.Issued("tok-" + fetches, clock.instant().plus(Duration.ofSeconds(300)));
        };
        TossRestClient c = new TossRestClient(http, shortLived, clock, new TossRateLimiter(clock, clock::advance));
        for (int i = 0; i < 5; i++) {
            http.respond(200, PRICES);
            c.get("/api/v1/prices", Map.of("symbols", "005930"));
            clock.advance(Duration.ofSeconds(20));
        }
        assertThat(fetches).isEqualTo(1); // 100초까지는 처음 토큰

        clock.advance(Duration.ofSeconds(60)); // 발급 뒤 160초 > 수명 절반 150초
        http.respond(200, PRICES);
        c.get("/api/v1/prices", Map.of("symbols", "005930"));
        assertThat(fetches).isEqualTo(2);
    }

    @Test
    void 반감기_조정은_긴_토큰을_건드리지_않고_실제_만료를_넘기지_않는다() {
        TokenCache.Issued day = new TokenCache.Issued("t", T0.plus(Duration.ofDays(1)));
        assertThat(TossRestClient.withRefreshAtHalfLife(day, T0)).isSameAs(day);

        TokenCache.Issued shortOne = new TokenCache.Issued("t", T0.plus(Duration.ofSeconds(300)));
        TokenCache.Issued adjusted = TossRestClient.withRefreshAtHalfLife(shortOne, T0);
        // 캐시는 adjusted.expiresAt - 10분에 다시 받는다 = T0 + 150초 < 실제 만료 T0 + 300초
        assertThat(adjusted.expiresAt().minus(TossRestClient.REFRESH_MARGIN)).isEqualTo(T0.plusSeconds(150));
        assertThat(adjusted.value()).isEqualTo("t");

        TokenCache.Issued expired = new TokenCache.Issued("t", T0.minusSeconds(5));
        assertThat(TossRestClient.withRefreshAtHalfLife(expired, T0).expiresAt().minus(TossRestClient.REFRESH_MARGIN))
                .isEqualTo(T0);
    }

    @Test
    void GET에_토큰을_싣고_result를_돌려주며_계좌_헤더는_넣지_않는다() throws Exception {
        http.respond(200, PRICES);

        JsonNode result = client.get("/api/v1/prices", Map.of("symbols", "005930,000660"));

        assertThat(result.get(0).path("lastPrice").asText()).isEqualTo("71000");
        FakeHttp.Call call = http.calls.get(0);
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.uri()).hasToString("https://openapi.tossinvest.com/api/v1/prices?symbols=005930,000660");
        assertThat(call.headers()).containsEntry("Authorization", "Bearer tok-1").doesNotContainKey("X-Tossinvest-Account");
        assertThat(client.health()).isEqualTo(TossRestClient.Health.OK);
    }

    @Test
    void 쿼리_값의_플러스와_앰퍼샌드는_인코딩한다() throws Exception {
        http.respond(200, "{\"result\":{\"candles\":[],\"nextBefore\":null}}");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", "005930");
        q.put("interval", "1m");
        q.put("before", "2026-03-25T09:00:00+09:00&x=1");

        client.get("/api/v1/candles", q);

        assertThat(http.calls.get(0).uri().getRawQuery())
                .isEqualTo("symbol=005930&interval=1m&before=2026-03-25T09%3A00%3A00%2B09%3A00%26x%3D1");
    }

    @Test
    void 만료_토큰이면_한_번만_새로_받아_다시_부른다() throws Exception {
        http.respond(401, error("expired-token")).respond(200, PRICES);

        client.get("/api/v1/prices", Map.of("symbols", "005930"));

        assertThat(fetches).isEqualTo(2);
        assertThat(http.calls).hasSize(2);
        assertThat(http.calls.get(1).headers()).containsEntry("Authorization", "Bearer tok-2");

        http.respond(401, error("invalid-token")).respond(401, error("invalid-token"));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOfSatisfying(TossApiException.class,
                        e -> assertThat(e.kind()).isEqualTo(TossApiException.Kind.TOKEN_INVALID));
        assertThat(http.calls).hasSize(4); // 두 번째 401에서는 더 다시 부르지 않는다
    }

    @Test
    void 다른_곳에서_재발급돼_무효가_된_토큰은_바로_다시_받지_않는다() throws Exception {
        http.respond(401, error("token-revoked"));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOfSatisfying(TossApiException.class,
                        e -> assertThat(e.kind()).isEqualTo(TossApiException.Kind.TOKEN_REVOKED));
        assertThat(fetches).isEqualTo(1);
        assertThat(client.health()).isEqualTo(TossRestClient.Health.DEGRADED);

        clock.advance(TossRestClient.REVOKED_COOLDOWN.minusSeconds(1));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOf(TossApiException.class);
        assertThat(http.calls).hasSize(1); // 쉬는 동안은 네트워크도 발급도 없다
        assertThat(fetches).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        http.respond(200, PRICES);
        client.get("/api/v1/prices", Map.of("symbols", "005930"));
        assertThat(fetches).isEqualTo(2);
        assertThat(client.health()).isEqualTo(TossRestClient.Health.OK);
    }

    @Test
    void 자격_증명_거절은_60초_하한을_지키고_3회_이어지면_DEGRADED다() throws Exception {
        for (int i = 0; i < 3; i++) {
            issue.add(new CredentialsRejectedException("토스 토큰 발급 거절: HTTP 401"));
        }
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOf(CredentialsRejectedException.class);
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOf(CredentialsRejectedException.class);
        assertThat(fetches).isEqualTo(1); // 60초 안에는 발급 요청을 다시 보내지 않는다
        assertThat(client.health()).isEqualTo(TossRestClient.Health.UNKNOWN);

        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOf(CredentialsRejectedException.class);
        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOf(CredentialsRejectedException.class);
        assertThat(fetches).isEqualTo(3);
        assertThat(client.health()).isEqualTo(TossRestClient.Health.DEGRADED);

        clock.advance(Duration.ofMinutes(2));
        http.respond(200, PRICES);
        client.get("/api/v1/prices", Map.of("symbols", "005930"));
        assertThat(client.health()).isEqualTo(TossRestClient.Health.OK);
        assertThat(http.calls).hasSize(1);
    }

    @Test
    void 발급이_다른_이유로_실패해도_60초_뒤에_다시_받는다() throws Exception {
        issue.add(new IOException("토스 토큰 발급 실패: HTTP 500"));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isNotInstanceOf(CredentialsRejectedException.class);
        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .hasMessageContaining("대기");
        assertThat(fetches).isEqualTo(1);
        assertThat(client.health()).isEqualTo(TossRestClient.Health.UNKNOWN);
    }

    @Test
    void 거부_뒤_발급_간격은_2분부터_두_배씩_30분까지다() {
        assertThat(TossRestClient.rejectedWait(1)).isEqualTo(Duration.ofSeconds(60));
        assertThat(TossRestClient.rejectedWait(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(TossRestClient.rejectedWait(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(TossRestClient.rejectedWait(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(TossRestClient.rejectedWait(6)).isEqualTo(Duration.ofMinutes(16));
        assertThat(TossRestClient.rejectedWait(7)).isEqualTo(Duration.ofMinutes(30));
        assertThat(TossRestClient.rejectedWait(100)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void 오류는_코드와_요청_식별자만_싣고_응답_문구는_싣지_않는다() {
        http.respond(429, "{\"error\":{\"requestId\":\"01HXYZ\",\"code\":\"rate-limit-exceeded\","
                + "\"message\":\"secret-like-text\"}}", Map.of("retry-after", "2"));

        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOfSatisfying(TossApiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(TossApiException.Kind.RATE_LIMITED);
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(2));
                    assertThat(e.code()).isEqualTo("rate-limit-exceeded");
                    assertThat(e.getMessage()).contains("HTTP 429", "rate-limit-exceeded", "01HXYZ")
                            .doesNotContain("secret-like-text");
                });
    }

    @Test
    void 상태별_분류() {
        http.respond(404, error("stock-not-found")).respond(400, error("unsupported-symbol"))
                .respond(500, "<html>").respond(403, error("forbidden"));
        TossApiException.Kind[] expected = {
            TossApiException.Kind.NOT_FOUND, TossApiException.Kind.BAD_REQUEST,
            TossApiException.Kind.SERVER_ERROR, TossApiException.Kind.FORBIDDEN};
        for (TossApiException.Kind k : expected) {
            assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                    .isInstanceOfSatisfying(TossApiException.class, e -> assertThat(e.kind()).isEqualTo(k));
        }
        assertThat(client.health()).isEqualTo(TossRestClient.Health.UNKNOWN);
    }

    @Test
    void 성공_본문에_result가_없으면_실패다() {
        http.respond(200, "{\"ok\":true}");

        assertThatThrownBy(() -> client.get("/api/v1/prices", Map.of("symbols", "005930")))
                .isInstanceOfSatisfying(TossApiException.class,
                        e -> assertThat(e.kind()).isEqualTo(TossApiException.Kind.OTHER));
    }

    private static String error(String code) {
        return "{\"error\":{\"requestId\":\"01HXYZ\",\"code\":\"" + code + "\",\"message\":\"m\"}}";
    }
}
