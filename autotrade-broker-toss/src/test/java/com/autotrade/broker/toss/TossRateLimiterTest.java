package com.autotrade.broker.toss;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.marketdata.live.HttpPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TossRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-10T00:00:00Z"));
    private final List<Duration> slept = new ArrayList<>();
    private final TossRateLimiter limiter = new TossRateLimiter(clock, d -> {
        slept.add(d);
        clock.advance(d);
    });

    @Test
    void 처음에는_공식_한도와_초당_10회_중_작은_값이다() throws Exception {
        limiter.acquire("MARKET_DATA");
        limiter.acquire("MARKET_DATA");
        limiter.acquire("MARKET_DATA_CHART"); // 다른 그룹은 따로 센다

        assertThat(slept).containsExactly(Duration.ofMillis(100));
        assertThat(limiter.perSecond("MARKET_DATA")).isEqualTo(10);
        assertThat(limiter.perSecond("STOCK_ALL")).isEqualTo(1);
        assertThat(limiter.perSecond("MARKET_INFO")).isEqualTo(3);
        assertThat(limiter.perSecond("AUTH")).isEqualTo(5);
        assertThat(limiter.perSecond("NEW_GROUP")).isEqualTo(1);
    }

    @Test
    void 초당_1회_그룹은_1초_간격이다() throws Exception {
        limiter.acquire("STOCK_ALL");
        limiter.acquire("STOCK_ALL");

        assertThat(slept).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void 응답의_X_RateLimit_Limit을_따르되_공식_한도를_넘지_않는다() throws Exception {
        limiter.onResponse("MARKET_DATA", ok(Map.of("x-ratelimit-limit", "5")));
        limiter.acquire("MARKET_DATA");
        limiter.acquire("MARKET_DATA");
        assertThat(slept).containsExactly(Duration.ofMillis(200));

        limiter.onResponse("MARKET_DATA", ok(Map.of("x-ratelimit-limit", "1000")));
        assertThat(limiter.perSecond("MARKET_DATA")).isEqualTo(15);

        limiter.onResponse("MARKET_DATA", ok(Map.of("x-ratelimit-limit", "abc")));
        assertThat(limiter.perSecond("MARKET_DATA")).isEqualTo(15);
    }

    @Test
    void 남은_토큰이_0이면_Reset초_동안_쉰다() throws Exception {
        limiter.acquire("STOCK");
        limiter.onResponse("STOCK", ok(Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", "2")));
        limiter.acquire("STOCK");

        assertThat(slept).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void 한도_초과면_Retry_After초_동안_쉬고_지나치게_길면_60초로_자른다() throws Exception {
        limiter.onResponse("RANKING", new HttpPort.Response(429, "", Map.of("retry-after", "3")));
        limiter.acquire("RANKING");
        limiter.onResponse("SECTOR", new HttpPort.Response(429, "", Map.of("retry-after", "999")));
        limiter.acquire("SECTOR");
        limiter.onResponse("AUTH", new HttpPort.Response(429, "", Map.of()));
        limiter.acquire("AUTH");

        assertThat(slept).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofSeconds(1));
    }

    @Test
    void 한_그룹이_쉬는_동안_잠금을_쥐지_않아_다른_그룹은_바로_지나간다() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TossRateLimiter real = new TossRateLimiter(clock, d -> {
            sleeping.countDown();
            release.await();
        });
        real.onResponse("RANKING", new HttpPort.Response(429, "", Map.of("retry-after", "60")));
        Thread blocked = new Thread(() -> {
            try {
                real.acquire("RANKING");
            } catch (Exception ignored) {
                // 테스트 종료
            }
        });
        blocked.start();
        try {
            assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            real.acquire("MARKET_DATA"); // 잠금을 쥔 채 잤다면 여기서 멈춘다
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            blocked.join(5000);
        }
    }

    @Test
    void 대기_상한을_넘으면_자리를_잡지_않고_바로_실패한다() throws Exception {
        limiter.onResponse("MARKET_DATA", new HttpPort.Response(429, "", Map.of("retry-after", "3")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limiter.acquire("MARKET_DATA", Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(TossApiException.class,
                        e -> assertThat(e.kind()).isEqualTo(TossApiException.Kind.RATE_LIMITED));
        assertThat(slept).isEmpty();

        limiter.acquire("MARKET_DATA", Duration.ofSeconds(5)); // 상한 안이면 기다린다
        assertThat(slept).containsExactly(Duration.ofSeconds(3));
    }

    private static HttpPort.Response ok(Map<String, String> headers) {
        return new HttpPort.Response(200, "{}", headers);
    }
}
