package com.autotrade.selection.profile;

import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

/**
 * 선정 결과 갱신 주기(선정 프로필 예약 키 {@code refresh}, ADR-007 2.1·2.6). 단타는 단타용 갱신 주기 집합
 * ({@link #PRE_OPEN_DAILY}, {@link #PRE_OPEN_DAILY_LAG1}, {@link #INTRADAY_AT})만, 스윙·중기는 장 마감 뒤 갱신만 쓴다
 * (DB {@code selection_profile} CHECK "단타 ⇔ 단타용 갱신 주기 집합"과 같은 규칙, V3·V4 마이그레이션, D-127·D-137).
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
    INTRADAY_AT,
    /**
     * 기준 데이터일을 하루 늦춘 장전 선정(ADR-012 3.7 (a), D-137). 적용일 D 하루, 계산은 {@link #PRE_OPEN_DAILY}가 D의 직전 거래일
     * D'에 적용할 결과를 만드는 것과 같다: 일봉·상장주식수는 D'의 직전 거래일까지, 재무·공시는 D' 전날까지만 읽는다. KRX 일봉이
     * 다음 날 장 시작 전까지 게시되지 않아(2026-10-21 사용자 PC 확인) 장전에 D의 직전 거래일 일봉을 받을 수 없을 때 쓴다. 단타 전용.
     */
    PRE_OPEN_DAILY_LAG1;

    /** 단타용 갱신 주기인지(ADR-007 2.1 "단타 ⇔ 단타용 갱신 주기 집합"). */
    public boolean intraday() {
        return this == PRE_OPEN_DAILY || this == PRE_OPEN_DAILY_LAG1 || this == INTRADAY_AT;
    }

    /** 장전 선정(일봉 스냅샷으로 장 전에 고정 파일을 만들어 그날 하루 적용)인지. */
    public boolean preOpen() {
        return this == PRE_OPEN_DAILY || this == PRE_OPEN_DAILY_LAG1;
    }

    /**
     * 선정 기준일 {@code applyFrom}의 결과를 계산할 때 쓰는 정보 시점. 그 날짜 전날까지 접수된 재무·공시와, 그 날짜의 직전 거래일
     * (기준 데이터일 c)까지의 일봉을 읽는다. {@link #PRE_OPEN_DAILY_LAG1}은 {@code applyFrom}의 직전 거래일, 나머지는
     * {@code applyFrom} 그대로다.
     *
     * @param previousTradingDay 거래일 → 직전 거래일(거래소 캘린더)
     * @throws IllegalStateException {@link #INTRADAY_AT}(일봉으로 계산하지 않음)
     */
    public LocalDate infoDate(LocalDate applyFrom, UnaryOperator<LocalDate> previousTradingDay) {
        if (this == INTRADAY_AT) {
            throw new IllegalStateException("장중 순위 선정(INTRADAY_AT)은 일봉 기준 데이터일이 없습니다");
        }
        return this == PRE_OPEN_DAILY_LAG1 ? previousTradingDay.apply(applyFrom) : applyFrom;
    }

    /**
     * 선정 기준일 {@code applyFrom}의 기준 데이터일 c(일봉 스냅샷이 담아야 하는 마지막 거래일). {@link #infoDate}의 직전 거래일이다:
     * {@link #PRE_OPEN_DAILY}는 T−1, {@link #PRE_OPEN_DAILY_LAG1}은 T−2(거래일 기준).
     *
     * @throws IllegalStateException {@link #INTRADAY_AT}
     */
    public LocalDate cutoff(LocalDate applyFrom, UnaryOperator<LocalDate> previousTradingDay) {
        return previousTradingDay.apply(infoDate(applyFrom, previousTradingDay));
    }

    /** 이 갱신 주기를 그 스타일의 선정에 쓸 수 있는지. */
    public boolean allows(TradingStyle style) {
        return intraday() == (style == TradingStyle.INTRADAY);
    }
}
