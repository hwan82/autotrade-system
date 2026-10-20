package com.autotrade.selection.profile;

import com.autotrade.strategy.TradingStyle;

/**
 * 선정 결과 갱신 주기(선정 프로필 예약 키 {@code refresh}, ADR-007 2.1·2.6). 단타는 단타용 갱신 주기 집합
 * ({@link #PRE_OPEN_DAILY}, {@link #INTRADAY_AT})만, 스윙·중기는 장 마감 뒤 갱신만 쓴다(DB {@code selection_profile} CHECK
 * "단타 ⇔ 단타용 갱신 주기 집합"과 같은 규칙, V3 마이그레이션, D-127).
 */
public enum SelectionRefresh {
    /** 적용일 D 하루. 기본은 D의 직전 거래일 장 마감 뒤에 만든다. 단타 전용. */
    PRE_OPEN_DAILY,
    /** 거래일 D 장 마감 뒤에 만들어 다음 거래일 하루 적용. */
    POST_CLOSE_DAILY,
    /** 그 주 마지막 거래일 장 마감 뒤에 만들어 다음 ISO 주 전체 적용. */
    POST_CLOSE_WEEKLY,
    /**
     * 장중 순위 선정(ADR-012 5.2 2차-a, D-127). 거래일 D의 정규장 시작부터 프로필 시각 {@code at}(거래소 현지) 전까지 받은 체결로
     * 등록 파일 {@code boards} 안 종목의 순위를 매겨, {@code at} 뒤 첫 이벤트부터 D 장 마감까지 적용한다. {@code at} 전에는 그 조합의
     * 신규 진입이 0건이다. 단타 전용. 일봉 스냅샷을 읽지 않고 입력 기록(시세 이벤트)만 쓴다.
     */
    INTRADAY_AT;

    /** 단타용 갱신 주기인지(ADR-007 2.1 "단타 ⇔ 단타용 갱신 주기 집합"). */
    public boolean intraday() {
        return this == PRE_OPEN_DAILY || this == INTRADAY_AT;
    }

    /** 이 갱신 주기를 그 스타일의 선정에 쓸 수 있는지. */
    public boolean allows(TradingStyle style) {
        return intraday() == (style == TradingStyle.INTRADAY);
    }
}
