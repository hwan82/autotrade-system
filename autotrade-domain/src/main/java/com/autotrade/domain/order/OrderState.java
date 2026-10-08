package com.autotrade.domain.order;

/**
 * 주문 상태. 전이 규칙(상태머신)은 가상·실전이 같은 코드로 공유하며 별도 작업에서 정의한다.
 *
 * <p>{@link #UNKNOWN}은 응답 시간 초과 등으로 접수 여부를 모르는 상태다. 이 상태에서는 재주문하지 않고
 * 반드시 조회부터 한다(프로젝트 지침 4장 2항).
 */
public enum OrderState {
    SUBMITTED,
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    UNKNOWN;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
