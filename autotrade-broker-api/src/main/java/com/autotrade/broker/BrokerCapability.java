package com.autotrade.broker;

/** 증권사 플러그인이 선언하는 능력. 엔진은 선언된 능력만 쓴다(broker-and-fault-design.md 3.2). */
public enum BrokerCapability {
    REALTIME_QUOTE,
    POLLING_QUOTE,
    ORDER,
    BALANCE,
    SERVER_SIDE_STOP
}
