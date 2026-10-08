package com.autotrade.broker;

/**
 * 증권사 플러그인 상태(broker-and-fault-design.md 3.1).
 * NOT_CONFIGURED → CONFIGURED → ACTIVE ⇄ DEGRADED.
 */
public enum BrokerStatus {
    /** 키가 없다. 로딩하지 않고 오류·알림도 내지 않는다. */
    NOT_CONFIGURED,
    /** 키는 있지만 인증 전. */
    CONFIGURED,
    /** 선언한 능력을 쓸 수 있다. */
    ACTIVE,
    /** 오류 누적. 시세는 다음 우선순위로 넘기고, 주문은 해당 계좌만 신규 진입을 멈춘다. */
    DEGRADED;

    public boolean isUsable() {
        return this == ACTIVE;
    }
}
