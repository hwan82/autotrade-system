package com.autotrade.domain.market;

/** 종목 거래 상태. 세부 사유(정적/동적 VI, LULD 등)는 시장별 소스가 매핑한다. */
public enum InstrumentStatus {
    TRADING,
    /** 변동성 완화장치(KRX VI) 또는 LULD(US) 등 일시 단일가·정지. */
    VOLATILITY_INTERRUPTION,
    HALTED,
    CLOSED
}
