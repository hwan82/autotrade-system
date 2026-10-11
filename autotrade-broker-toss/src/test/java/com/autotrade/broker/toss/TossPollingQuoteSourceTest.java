package com.autotrade.broker.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.live.TokenCache;
import com.autotrade.marketdata.polling.LastPrice;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TossPollingQuoteSourceTest {

    private static final Instant T0 = Instant.parse("2026-10-10T00:30:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final FakeHttp http = new FakeHttp();
    private final TossRestClient client = new TossRestClient(http,
            () -> new TokenCache.Issued("tok", clock.instant().plus(Duration.ofDays(1))), clock,
            new TossRateLimiter(clock, clock::advance));
    private final TossPollingQuoteSource source = new TossPollingQuoteSource(client, clock);

    @Test
    void 시장별로_나눠_묻고_응답을_그대로_옮긴다() throws Exception {
        http.respond(200, "{\"result\":["
                + "{\"symbol\":\"005930\",\"timestamp\":\"2026-10-10T09:29:58+09:00\",\"lastPrice\":\"71000\",\"currency\":\"KRW\"},"
                + "{\"symbol\":\"000660\",\"timestamp\":\"2026-10-10T09:29:59+09:00\",\"lastPrice\":\"185500.00\",\"currency\":\"KRW\"}]}");
        http.respond(200, "{\"result\":["
                + "{\"symbol\":\"aapl\",\"timestamp\":\"2026-10-09T15:59:59-04:00\",\"lastPrice\":\"227.31\",\"currency\":\"USD\"}]}");

        Map<Symbol, LastPrice> out = source.poll(set(Symbol.krx("005930"), Symbol.krx("000660"), Symbol.us("AAPL")));

        assertThat(http.calls).hasSize(2);
        assertThat(http.calls.get(0).uri().getRawQuery()).isEqualTo("symbols=000660,005930");
        assertThat(http.calls.get(1).uri().getRawQuery()).isEqualTo("symbols=AAPL");
        assertThat(out).containsOnlyKeys(Symbol.krx("005930"), Symbol.krx("000660"), Symbol.us("AAPL"));
        LastPrice samsung = out.get(Symbol.krx("005930"));
        assertThat(samsung.price()).isEqualByComparingTo(new BigDecimal("71000"));
        assertThat(samsung.priceTime()).isEqualTo(Instant.parse("2026-10-10T00:29:58Z"));
        assertThat(samsung.receivedAt()).isEqualTo(T0); // 국내 묶음을 받은 시각(미국 묶음 전 한도 대기로 시계가 간다)
        assertThat(samsung.sourceId()).isEqualTo("TOSS-POLL");
        assertThat(out.get(Symbol.krx("000660")).price()).isEqualTo(new BigDecimal("185500").stripTrailingZeros());
        assertThat(out.get(Symbol.us("AAPL")).priceTime()).isEqualTo(Instant.parse("2026-10-09T19:59:59Z"));
    }

    @Test
    void 값을_지어내지_않는다() throws Exception {
        http.respond(200, "{\"result\":["
                + "{\"symbol\":\"005930\",\"timestamp\":null,\"lastPrice\":\"71000\",\"currency\":\"KRW\"},"
                + "{\"symbol\":\"000660\",\"timestamp\":\"2026-10-10T09:29:59+09:00\",\"lastPrice\":\"1\",\"currency\":\"USD\"},"
                + "{\"symbol\":\"035420\",\"timestamp\":\"2026-10-10T09:29:59+09:00\",\"lastPrice\":205000,\"currency\":\"KRW\"},"
                + "{\"symbol\":\"035720\",\"timestamp\":\"2026-10-10T09:29:59+09:00\",\"lastPrice\":\"0\",\"currency\":\"KRW\"},"
                + "{\"symbol\":\"051910\",\"timestamp\":\"2026-10-10T09:29:59+09:00\",\"lastPrice\":\"x\",\"currency\":\"KRW\"},"
                + "{\"symbol\":\"999999\",\"timestamp\":\"2026-10-10T09:29:59+09:00\",\"lastPrice\":\"100\",\"currency\":\"KRW\"},"
                + "{\"symbol\":\"068270\",\"timestamp\":\"bad\",\"lastPrice\":\"100\",\"currency\":\"KRW\"}]}");

        Map<Symbol, LastPrice> out = source.poll(set(Symbol.krx("005930"), Symbol.krx("000660"), Symbol.krx("035420"),
                Symbol.krx("035720"), Symbol.krx("051910"), Symbol.krx("068270")));

        // 시각 없음, 통화 불일치, 숫자 노드(실수 변환 위험), 0원, 숫자 아님, 묻지 않은 종목, 시각 형식 오류는 모두 뺀다
        assertThat(out).isEmpty();
    }

    @Test
    void 결과가_배열이_아니면_실패다() {
        http.respond(200, "{\"result\":{}}");

        assertThatThrownBy(() -> source.poll(set(Symbol.krx("005930")))).isInstanceOf(IOException.class);
    }

    @Test
    void 한_번에_200종목까지만_묻는다() {
        SortedSet<Symbol> many = new TreeSet<>();
        for (int i = 0; i < 201; i++) {
            many.add(Symbol.krx(String.format("%06d", i)));
        }

        assertThat(source.maxSymbolsPerCall()).isEqualTo(200);
        assertThatThrownBy(() -> source.poll(many)).isInstanceOf(IllegalArgumentException.class);
        assertThat(http.calls).isEmpty();
    }

    /**
     * 발급 응답이 발급 그룹을 30초 막은 뒤(Remaining 0, Reset 30) 조회가 401 만료를 받아 다시 발급해야 하는 경우에도
     * 폴링은 30초를 기다리지 않고 1초 안에 실패한다(PR #41 독립 QA F1).
     */
    @Test
    void 토큰_재발급이_발급_한도에_막혀도_폴링은_기다리지_않고_실패한다() throws Exception {
        FakeHttp tossHttp = new FakeHttp();
        TossRateLimiter limiter = new TossRateLimiter(clock, clock::advance);
        TossRestClient real = new TossRestClient(tossHttp,
                new TossTokenClient(tossHttp, new com.autotrade.broker.BrokerCredentials("id-placeholder",
                        "secret-placeholder"), clock, limiter), clock, limiter);
        TossPollingQuoteSource poller = new TossPollingQuoteSource(real, clock);
        tossHttp.respond(200, "{\"access_token\":\"tok-1\",\"expires_in\":86400}",
                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "30"));
        tossHttp.respond(200, "{\"result\":[{\"symbol\":\"005930\",\"timestamp\":\"2026-10-10T09:29:58+09:00\","
                + "\"lastPrice\":\"71000\",\"currency\":\"KRW\"}]}");
        assertThat(poller.poll(set(Symbol.krx("005930")))).hasSize(1);

        tossHttp.respond(401, "{\"error\":{\"code\":\"expired-token\"}}");
        Instant before = clock.instant();
        assertThatThrownBy(() -> poller.poll(set(Symbol.krx("005930")))).isInstanceOf(IOException.class);

        assertThat(Duration.between(before, clock.instant())).isLessThanOrEqualTo(TossPollingQuoteSource.MAX_RATE_WAIT);
        assertThat(tossHttp.calls).hasSize(3); // 발급, 조회, 만료된 조회. 막힌 발급은 보내지 않았다
        assertThat(tossHttp.responses).isEmpty();
    }

    @Test
    void 폴링은_조회_한도_대기_상한을_넘기면_보내지_않고_실패한다() throws Exception {
        http.respond(429, "{\"error\":{\"code\":\"rate-limited\"}}", Map.of("retry-after", "5"));
        assertThatThrownBy(() -> source.poll(set(Symbol.krx("005930")))).isInstanceOf(IOException.class);
        Instant before = clock.instant();

        assertThatThrownBy(() -> source.poll(set(Symbol.krx("005930"))))
                .isInstanceOfSatisfying(TossApiException.class,
                        e -> assertThat(e.kind()).isEqualTo(TossApiException.Kind.RATE_LIMITED));
        assertThat(clock.instant()).isEqualTo(before);
        assertThat(http.calls).hasSize(1);
    }

    private static SortedSet<Symbol> set(Symbol... symbols) {
        return new TreeSet<>(java.util.List.of(symbols));
    }
}
