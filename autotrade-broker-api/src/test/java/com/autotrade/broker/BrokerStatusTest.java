package com.autotrade.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrokerStatusTest {

    @Test
    void 상태는_설계서의_네_가지다() {
        // 상태가 늘거나 줄면 broker-and-fault-design.md 3.1과 장애 대응 정책을 함께 고쳐야 한다
        assertThat(BrokerStatus.values())
                .containsExactly(
                        BrokerStatus.NOT_CONFIGURED, BrokerStatus.CONFIGURED, BrokerStatus.ACTIVE, BrokerStatus.DEGRADED);
    }
}
