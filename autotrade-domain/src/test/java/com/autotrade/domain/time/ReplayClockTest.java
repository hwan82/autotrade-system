package com.autotrade.domain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReplayClockTest {

    private static final Instant START = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void 앞으로만_움직인다() {
        ReplayClock clock = new ReplayClock(START, ZoneOffset.UTC);
        clock.advanceTo(START.plusSeconds(5));
        assertThat(clock.instant()).isEqualTo(START.plusSeconds(5));
        clock.advanceTo(START.plusSeconds(5));

        assertThatThrownBy(() -> clock.advanceTo(START)).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.instant()).isEqualTo(START.plusSeconds(5));
    }

    @Test
    void 시간대만_바꾼_시계는_같은_시각을_공유한다() {
        ReplayClock clock = new ReplayClock(START, ZoneOffset.UTC);
        Clock seoul = clock.withZone(ZoneId.of("Asia/Seoul"));
        clock.advanceTo(START.plusSeconds(60));

        assertThat(seoul.instant()).isEqualTo(START.plusSeconds(60));
        assertThat(seoul.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
