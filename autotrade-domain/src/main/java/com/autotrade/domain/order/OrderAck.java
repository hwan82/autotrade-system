package com.autotrade.domain.order;

import java.util.Objects;

/**
 * 주문·취소 요청에 대한 즉시 응답.
 *
 * @param brokerOrderId 증권사(또는 가상 거래소) 주문번호. 접수 전이거나 모르면 null
 * @param message 거절·오류 사유. 비밀값을 넣지 않는다
 */
public record OrderAck(IdempotencyKey key, OrderState state, String brokerOrderId, String message) {

    public OrderAck {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        message = message == null ? "" : message;
    }
}
