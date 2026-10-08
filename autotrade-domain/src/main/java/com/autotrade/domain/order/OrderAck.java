package com.autotrade.domain.order;

import java.util.Objects;

/**
 * 주문·취소 요청에 대한 즉시 응답.
 *
 * @param state 이 키 주문의 현재 상태. {@code duplicateKey}면 원주문의 상태다
 * @param duplicateKey 이미 전송한 키로 다시 요청됐다는 뜻. 새 주문은 만들어지지 않았다.
 *     증권사 거절({@link OrderState#REJECTED})과 구분하기 위해 따로 둔다
 * @param brokerOrderId 증권사(또는 가상 거래소) 주문번호. 접수 전이거나 모르면 null
 * @param message 거절·오류 사유. 비밀값을 넣지 않는다
 */
public record OrderAck(
        IdempotencyKey key, OrderState state, boolean duplicateKey, String brokerOrderId, String message) {

    public OrderAck {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        message = message == null ? "" : message;
    }
}
