package com.autotrade.risk;

/** 블랙리스트 등록 유형(RSK-12). */
public enum BlacklistType {
    /** 사용자가 UI에서 지정한 제외 종목. 거절 규칙 ID {@code BLACKLIST}. */
    MANUAL("BLACKLIST"),
    /** 시스템 주문 원장에 없는 보유(수동 보유)를 대사에서 자동 감지한 종목. 거절 규칙 ID {@code MANUAL_HOLDING}. */
    AUTO_ASSET("MANUAL_HOLDING");

    private final String ruleId;

    BlacklistType(String ruleId) {
        this.ruleId = ruleId;
    }

    /** 이 유형으로 막힌 주문의 거절 규칙 ID. */
    public String ruleId() {
        return ruleId;
    }
}
