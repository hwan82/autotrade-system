package com.autotrade.marketdata.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenCacheTest {

    @Test
    void 실패한_요청의_토큰이_지금_토큰일_때만_버린다() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        AtomicInteger issued = new AtomicInteger();
        TokenCache cache = new TokenCache(
                () -> new TokenCache.Issued("T" + issued.incrementAndGet(), Instant.parse("2026-11-03T00:00:00Z")),
                clock, java.time.Duration.ofHours(1));
        assertThat(cache.get()).isEqualTo("T1");
        cache.invalidate();
        assertThat(cache.get()).isEqualTo("T2");

        // 옛 토큰 T1의 늦은 실패는 새 토큰 T2를 버리지 않는다
        assertThat(cache.invalidateIfCurrent("T1")).isFalse();
        assertThat(cache.get()).isEqualTo("T2");
        assertThat(issued).hasValue(2);

        assertThat(cache.invalidateIfCurrent("T2")).isTrue();
        assertThat(cache.get()).isEqualTo("T3");
    }
}
