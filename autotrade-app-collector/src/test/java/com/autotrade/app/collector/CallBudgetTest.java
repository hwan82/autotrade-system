package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallBudgetTest {

    @TempDir
    Path dir;

    // 2026-10-10 14:59Z = 서울 23:59, 15:00Z = 서울 다음 날 00:00
    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void 상한에_닿으면_막고_같은_날_다른_실행도_합산한다() {
        CallBudget a = new CallBudget(dir, "krx", 2, at("2026-10-10T01:00:00Z"));
        a.acquire();
        CallBudget b = new CallBudget(dir, "krx", 2, at("2026-10-10T14:59:00Z"));
        b.acquire();
        assertThatThrownBy(b::acquire).isInstanceOf(CallBudget.BudgetExhausted.class);
        assertThat(b.usedToday()).isEqualTo(2);
        // 서울 날짜가 바뀌면 새로 센다
        CallBudget next = new CallBudget(dir, "krx", 2, at("2026-10-10T15:00:00Z"));
        next.acquire();
        assertThat(next.usedToday()).isEqualTo(1);
        // 소스마다 따로 센다
        new CallBudget(dir, "opendart", 1, at("2026-10-10T01:00:00Z")).acquire();
    }

    @Test
    void 동시에_돌아도_합산이_빠지지_않는다() throws Exception {
        int threads = 4;
        int each = 25;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                CallBudget b = new CallBudget(dir, "krx", 1000, at("2026-10-10T01:00:00Z"));
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < each; i++) {
                        b.acquire();
                    }
                }));
            }
            for (var f : fs) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(new CallBudget(dir, "krx", 1000, at("2026-10-10T01:00:00Z")).usedToday()).isEqualTo(threads * each);
    }

    @Test
    void 한도_초과_응답_뒤에는_그날_더_부르지_않는다() {
        CallBudget a = new CallBudget(dir, "opendart", 100, at("2026-10-10T01:00:00Z"));
        a.acquire();
        a.stopForToday();
        CallBudget again = new CallBudget(dir, "opendart", 100, at("2026-10-10T05:00:00Z"));
        assertThatThrownBy(again::acquire).isInstanceOf(CallBudget.BudgetExhausted.class).hasMessageContaining("멈췄");
        new CallBudget(dir, "opendart", 100, at("2026-10-10T15:00:00Z")).acquire();
    }
}
