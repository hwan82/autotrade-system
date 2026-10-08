package com.autotrade.domain.market;

/**
 * 지원 시장. 미국은 1단계 범위(D-014). 시장별 차이는 {@link com.autotrade.domain.MarketRules}로만 다룬다.
 */
public enum Market {
    /** 한국거래소(코스피·코스닥). */
    KRX,
    /** 미국(NYSE·NASDAQ 등 정규장). */
    US
}
