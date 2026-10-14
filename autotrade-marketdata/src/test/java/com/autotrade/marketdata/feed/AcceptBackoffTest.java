package com.autotrade.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 연결 받기 실패가 이어지면 쉬는 시간이 늘고 로그는 드물어진다(risk-ops PR #87 R3). */
class AcceptBackoffTest {

    @Test
    void delayDoublesUpToOneSecondAndResetsOnSuccess() {
        AcceptBackoff b = new AcceptBackoff();
        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            b.failed();
            delays.add(b.delayMillis());
        }
        assertThat(delays).containsExactly(10L, 20L, 40L, 80L, 160L, 320L, 640L, 1000L, 1000L, 1000L);
        for (int i = 0; i < 1_000_000; i++) {
            b.failed();
        }
        assertThat(b.delayMillis()).isEqualTo(AcceptBackoff.MAX_MILLIS);
        b.succeeded();
        assertThat(b.delayMillis()).isZero();
        assertThat(b.failed()).isEqualTo(1);
        assertThat(b.delayMillis()).isEqualTo(AcceptBackoff.FIRST_MILLIS);
    }

    @Test
    void logsOnlyAtPowersOfTwo() {
        List<Long> logged = new ArrayList<>();
        for (long n = 1; n <= 1000; n++) {
            if (AcceptBackoff.shouldLog(n)) {
                logged.add(n);
            }
        }
        assertThat(logged).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L);
    }
}
