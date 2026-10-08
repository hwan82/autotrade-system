package com.autotrade.strategy.catalog;

/** 장 마감 보유 정책(STR-09). 장중 프로필은 {@link #FLATTEN}만 쓴다. */
public enum EodPolicy {
    /** {@code exit.eod-time}에 전량 청산. */
    FLATTEN,
    /** 다음 거래일로 이월(스윙). */
    CARRY
}
