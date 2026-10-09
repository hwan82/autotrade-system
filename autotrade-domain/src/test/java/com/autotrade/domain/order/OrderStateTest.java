package com.autotrade.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class OrderStateTest {

    @Test
    void 상태_집합은_기획서_ORD_07과_같다() {
        // docs/spec/autotrade-spec.md ORD-07. 순서까지 맞춘다(전이표 작업이 이 순서를 기준으로 표를 만든다)
        assertThat(Arrays.stream(OrderState.values()).map(Enum::name)).containsExactly(
                "NEW", "SUBMITTING", "ACCEPTED", "PARTIALLY_FILLED", "FILLED",
                "CANCEL_REQUESTED", "CANCELED", "REJECTED", "EXPIRED", "UNKNOWN");
    }

    @Test
    void 체결_취소_거절_만료만_종결이다() {
        EnumSet<OrderState> terminal = EnumSet.of(
                OrderState.FILLED, OrderState.CANCELED, OrderState.REJECTED, OrderState.EXPIRED);
        for (OrderState s : OrderState.values()) {
            assertThat(s.isTerminal()).as(s.name()).isEqualTo(terminal.contains(s));
        }
    }

    @Test
    void 만료는_종결이다() {
        assertThat(OrderState.EXPIRED.isTerminal()).isTrue();
    }

    @Test
    void 미확정과_취소_요청_중은_종결이_아니다() {
        // UNKNOWN을 종결로 보면 조회 없이 끝난 주문으로 취급해 재발주 판단이 틀어진다(지침 4장 2항)
        assertThat(OrderState.UNKNOWN.isTerminal()).isFalse();
        // 취소 확인 전에 체결이 날 수 있다
        assertThat(OrderState.CANCEL_REQUESTED.isTerminal()).isFalse();
        assertThat(OrderState.NEW.isTerminal()).isFalse();
        assertThat(OrderState.SUBMITTING.isTerminal()).isFalse();
    }
}
