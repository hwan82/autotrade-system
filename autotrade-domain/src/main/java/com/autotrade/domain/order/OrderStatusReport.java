package com.autotrade.domain.order;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 주문 조회 결과.
 *
 * @param averageFillPrice 체결이 없으면 null
 */
public record OrderStatusReport(
        IdempotencyKey key, OrderState state, long orderedQuantity, long filledQuantity, BigDecimal averageFillPrice) {

    public OrderStatusReport {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        if (filledQuantity < 0 || filledQuantity > orderedQuantity) {
            throw new IllegalArgumentException("체결 수량이 범위를 벗어났습니다: " + filledQuantity + "/" + orderedQuantity);
        }
    }

    public long remainingQuantity() {
        return orderedQuantity - filledQuantity;
    }
}
