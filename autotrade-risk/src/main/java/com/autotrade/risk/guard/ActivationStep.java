package com.autotrade.risk.guard;

/**
 * 활성화 단계(RSK-01). 정해진 순서로만 통과하며, 마지막 단계를 통과해야 OPEN이 된다.
 * 단계 실행(증권사 인증, 세션 수립, 대사)은 엔진이 하고 Guard는 결과만 받는다.
 */
public enum ActivationStep {
    /** 증권사 인증(토큰 발급). */
    AUTHENTICATED,
    /** 주문 세션 수립. 이 단계부터 {@link OrderSessionPermit}을 받을 수 있다. */
    ORDER_SESSION,
    /** 첫 대사: 잔고·미체결 일치, 재시작 전 UNKNOWN 주문 확정(ORD-07). */
    FIRST_RECONCILIATION
}
