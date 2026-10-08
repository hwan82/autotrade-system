package com.autotrade.marketdata.rules;

/** 상품 유형. 같은 가격이라도 호가 단위가 다를 수 있어 {@link com.autotrade.domain.MarketRules#tickSize}가 쓴다. */
public enum InstrumentKind {
    STOCK,
    ETF,
    ETN
}
