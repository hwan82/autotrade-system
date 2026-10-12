package com.autotrade.app.collector;

/** KRX Open API 일별매매정보 서비스(시장별). 서비스 경로는 [가정](키 발급 뒤 첫 호출로 확인, ADR-008 2.3). */
public enum KrxBoard {
    KOSPI("stk_bydd_trd"),
    KOSDAQ("ksq_bydd_trd"),
    KONEX("knx_bydd_trd");

    public final String service;

    KrxBoard(String service) {
        this.service = service;
    }
}
