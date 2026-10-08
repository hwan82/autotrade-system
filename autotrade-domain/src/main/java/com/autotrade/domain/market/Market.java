package com.autotrade.domain.market;

import java.util.Currency;

/**
 * 지원 시장. 미국은 1단계 범위(D-014). 시장별 차이는 {@link com.autotrade.domain.MarketRules}로만 다룬다.
 */
public enum Market {
    /** 한국거래소(코스피·코스닥). */
    KRX(Currency.getInstance("KRW")),
    /** 미국(NYSE·NASDAQ 등 정규장). */
    US(Currency.getInstance("USD"));

    private final Currency currency;

    Market(Currency currency) {
        this.currency = currency;
    }

    /** 이 시장의 결제 통화. 계좌 통화와 같아야 한다(엔진은 환전하지 않음, D-015). */
    public Currency currency() {
        return currency;
    }
}
