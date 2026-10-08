package com.autotrade.domain.order;

/**
 * 주문 유형. 증권사마다 미국 시장가 지원이 달라 공통 방식은 "지정가 + 허용 여유폭"이다(overseas-design.md 3장).
 */
public enum OrderType {
    LIMIT,
    MARKET
}
