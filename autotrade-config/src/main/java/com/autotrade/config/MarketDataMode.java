package com.autotrade.config;

/** 실시간 시세 소스 운용 방식(D-009). */
public enum MarketDataMode {
    /** 실시간 소스 두 곳이 ACTIVE면 SHARDED, 아니면 FAILOVER(D-009 기본 동작). */
    AUTO,
    FAILOVER,
    SHARDED
}
