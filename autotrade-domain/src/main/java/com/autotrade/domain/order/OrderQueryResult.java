package com.autotrade.domain.order;

import java.util.Objects;

/**
 * 멱등 키 조회 결과. "없음"과 "모름"을 구분한다. 재발주는 {@link NotFound}일 때만 허용된다.
 */
public sealed interface OrderQueryResult
        permits OrderQueryResult.Found, OrderQueryResult.NotFound, OrderQueryResult.Indeterminate {

    /** 주문이 접수되어 있다. */
    record Found(OrderStatusReport report) implements OrderQueryResult {
        public Found {
            Objects.requireNonNull(report, "report");
        }
    }

    /**
     * 접수되지 않았음이 확정됐다. 증권사 반영 지연 시간이 지난 뒤 주문·체결 목록을 대조해 없을 때만 낸다.
     * 가상 거래소는 자기 원장에 없으면 즉시 낼 수 있다.
     */
    enum NotFound implements OrderQueryResult {
        INSTANCE
    }

    /**
     * 확인하지 못했다(조회 실패, 시간 초과, 반영 지연 시간 안). 재발주 금지. 조회를 다시 하거나 사람에게 알린다.
     *
     * @param reason 비밀값을 넣지 않는다
     */
    record Indeterminate(String reason) implements OrderQueryResult {
        public Indeterminate {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
