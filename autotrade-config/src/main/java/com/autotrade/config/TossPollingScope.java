package com.autotrade.config;

/** 토스 보유 종목 보호 폴링 범위(D-024: 토스 실시간은 2순위 시세 소스, 폴링은 전 시세 끊김 시 보유 종목 보호용). */
public enum TossPollingScope {
    HELD_ONLY,
    OFF
}
