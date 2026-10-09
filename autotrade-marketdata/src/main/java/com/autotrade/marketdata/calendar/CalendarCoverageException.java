package com.autotrade.marketdata.calendar;

import java.time.LocalDate;

/** 캘린더 데이터가 없는 날짜를 물었다. 추측하지 않고 실패한다(MD-08, 하드코딩 금지). */
public final class CalendarCoverageException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public CalendarCoverageException(String market, LocalDate date, LocalDate from, LocalDate to) {
        super(market + " 캘린더에 " + date + " 데이터가 없습니다(수록 범위 " + from + " ~ " + to + ")");
    }
}
