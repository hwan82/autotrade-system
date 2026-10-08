package com.autotrade.config;

/** 토스 폴링 범위. 토스는 실시간이 없어 보유 종목 보호용으로만 쓴다(D-008). */
public enum TossPollingScope {
    HELD_ONLY,
    OFF
}
