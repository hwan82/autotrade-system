package com.autotrade.broker.kis.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.marketdata.live.TokenCache;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** D-054 R2: 발급 시도(실패 포함) 사이 최소 60초. */
class KisTokenGateTest {

    @Test
    void 실패한_시도도_60초_안에는_다시_부르지_않는다() throws Exception {
        OrderFakes.MutableClock clock = new OrderFakes.MutableClock(Instant.parse("2026-11-02T00:00:00Z"));
        int[] calls = {0};
        KisTokenGate gate = new KisTokenGate(() -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new IOException("발급 실패");
            }
            return new TokenCache.Issued("T", clock.instant().plus(Duration.ofDays(1)));
        }, clock);
        assertThatThrownBy(gate::fetch).hasMessage("발급 실패");
        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(gate::fetch).hasMessageContaining("60초");
        assertThat(calls[0]).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(gate.fetch().value()).isEqualTo("T");
        assertThat(calls[0]).isEqualTo(2);
    }
}
