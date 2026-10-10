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
        assertThat(samsung.receivedAt()).isEqualTo(clock.instant());
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

    private static SortedSet<Symbol> set(Symbol... symbols) {
        return new TreeSet<>(java.util.List.of(symbols));
    }
}
