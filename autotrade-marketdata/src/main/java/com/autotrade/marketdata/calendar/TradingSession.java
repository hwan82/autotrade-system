package com.autotrade.marketdata.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 하루의 정규장 구간(거래소 현지 시각). 개장 시각 포함, 폐장 시각 미포함 {@code [open, close)}.
 *
 * @param note 특별 운영일이면 사유(조기 폐장, 수능일 등), 아니면 빈 문자열
 */
public record TradingSession(LocalDate date, ZoneId zone, LocalTime open, LocalTime close, String note) {

    public TradingSession {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(close, "close");
        note = note == null ? "" : note;
        if (!close.isAfter(open)) {
            throw new IllegalArgumentException("폐장 시각은 개장 시각보다 뒤여야 합니다: " + date);
        }
    }

    /** 개장 순간. 서머타임은 시간대 데이터(tzdb)가 처리한다. */
    public Instant openInstant() {
        return date.atTime(open).atZone(zone).toInstant();
    }

    public Instant closeInstant() {
        return date.atTime(close).atZone(zone).toInstant();
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(openInstant()) && instant.isBefore(closeInstant());
    }
}
