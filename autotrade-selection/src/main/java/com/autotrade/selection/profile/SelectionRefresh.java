package com.autotrade.selection.profile;

import com.autotrade.strategy.TradingStyle;

/**
 * 선정 결과 갱신 주기(선정 프로필 예약 키 {@code refresh}, ADR-007 2.1·2.6). 단타는 {@link #PRE_OPEN_DAILY}만,
 * 스윙·중기는 장 마감 뒤 갱신만 쓴다(DB {@code selection_profile} CHECK "단타 ⇔ PRE_OPEN_DAILY"와 같은 규칙).
 */
public enum SelectionRefresh {
    /** 적용일 D 하루. 기본은 D의 직전 거래일 장 마감 뒤에 만든다. 단타 전용. */
    PRE_OPEN_DAILY,
    /** 거래일 D 장 마감 뒤에 만들어 다음 거래일 하루 적용. */
    POST_CLOSE_DAILY,
    /** 그 주 마지막 거래일 장 마감 뒤에 만들어 다음 ISO 주 전체 적용. */
    POST_CLOSE_WEEKLY;

    /** 이 갱신 주기를 그 스타일의 선정에 쓸 수 있는지. */
    public boolean allows(TradingStyle style) {
        return (this == PRE_OPEN_DAILY) == (style == TradingStyle.INTRADAY);
    }
}
