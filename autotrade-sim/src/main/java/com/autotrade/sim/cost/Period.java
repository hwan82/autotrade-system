package com.autotrade.sim.cost;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 비용 표의 적용 구간 한 줄(D-021 2항). 날짜는 체결 이벤트의 거래소 현지 날짜다.
 *
 * @param from 시작일(포함)
 * @param to 종료일(포함). null이면 열린 구간(표의 마지막 줄만 가능)
 */
public record Period<V extends CostValue>(LocalDate from, LocalDate to, V value) {

    public Period {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(value, "value");
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("구간 종료일이 시작일보다 앞섭니다: " + from + " ~ " + to);
        }
    }

    public static <V extends CostValue> Period<V> open(LocalDate from, V value) {
        return new Period<>(from, null, value);
    }

    public static <V extends CostValue> Period<V> closed(LocalDate from, LocalDate to, V value) {
        return new Period<>(from, Objects.requireNonNull(to, "to"), value);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }

    /** [start, end]와 겹치는지. end가 null이면 start부터 무한. */
    boolean overlaps(LocalDate start, LocalDate end) {
        boolean startsBeforeEnd = end == null || !from.isAfter(end);
        boolean endsAfterStart = to == null || !to.isBefore(start);
        return startsBeforeEnd && endsAfterStart;
    }

    String canonical() {
        return from + ".." + (to == null ? "" : to) + "=" + value.canonical();
    }
}
