package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.monitor.Reading;
import org.junit.jupiter.api.Test;

class OsMetricsSourceTest {

    private static OsReadings os(double cpu, long total, long free) {
        return new OsReadings() {
            @Override
            public double cpuLoad() {
                return cpu;
            }

            @Override
            public long totalMemoryBytes() {
                return total;
            }

            @Override
            public long freeMemoryBytes() {
                return free;
            }
        };
    }

    @Test
    void CPU와_메모리를_사용률로_낸다() {
        assertThat(new OsMetricsSource(os(0.25, 16_000, 4_000)).read()).containsExactly(
                new Reading(OsMetricsSource.CPU, 25.0, "%"), new Reading(OsMetricsSource.MEMORY, 75.0, "%"));
    }

    @Test
    void 경계값도_그대로_낸다() {
        assertThat(new OsMetricsSource(os(1.0, 100, 0)).read()).containsExactly(
                new Reading(OsMetricsSource.CPU, 100.0, "%"), new Reading(OsMetricsSource.MEMORY, 100.0, "%"));
        assertThat(new OsMetricsSource(os(0.0, 100, 100)).read()).containsExactly(
                new Reading(OsMetricsSource.CPU, 0.0, "%"), new Reading(OsMetricsSource.MEMORY, 0.0, "%"));
    }

    @Test
    void 모르거나_말이_안_되는_값은_빼서_판단_못_함이_되게_한다() {
        assertThat(new OsMetricsSource(os(-1, 100, 50)).read()).extracting(Reading::name).containsExactly(OsMetricsSource.MEMORY);
        assertThat(new OsMetricsSource(os(Double.NaN, 100, 50)).read()).extracting(Reading::name)
                .containsExactly(OsMetricsSource.MEMORY);
        assertThat(new OsMetricsSource(os(1.5, 100, 50)).read()).extracting(Reading::name).containsExactly(OsMetricsSource.MEMORY);
        assertThat(new OsMetricsSource(os(0.5, 0, 0)).read()).extracting(Reading::name).containsExactly(OsMetricsSource.CPU);
        assertThat(new OsMetricsSource(os(0.5, 100, -1)).read()).extracting(Reading::name).containsExactly(OsMetricsSource.CPU);
        assertThat(new OsMetricsSource(os(0.5, 100, 101)).read()).extracting(Reading::name).containsExactly(OsMetricsSource.CPU);
    }

    @Test
    void 이_JDK에서는_PC_값을_읽을_수_있다() {
        // 감시를 띄울 JDK(Temurin 21 등 HotSpot)는 com.sun.management 빈을 준다. 못 주면 감시는 기동을 거부한다(D-095)
        OsReadings real = PlatformOsReadings.create().orElseThrow();
        assertThat(real.totalMemoryBytes()).isPositive();
        assertThat(real.freeMemoryBytes()).isBetween(0L, real.totalMemoryBytes());
        assertThat(new OsMetricsSource(real).read()).extracting(Reading::name).contains(OsMetricsSource.MEMORY);
    }
}
