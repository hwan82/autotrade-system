package com.autotrade.selection.calc;

import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 선정 계산이 쓰는 거래일 목록(거래소 현지 날짜). 수록 범위 밖을 물으면 {@link IllegalStateException}으로 실패한다(휴장과
 * 범위 밖을 구분하지 못한 채 계산하지 않는다). 불변, 스레드 안전.
 */
public final class TradingDays {

    private final NavigableSet<LocalDate> days;
    private final LocalDate coverageFrom;
    private final LocalDate coverageTo;

    /** @param days 수록 범위 {@code [coverageFrom, coverageTo]} 안의 거래일 전부 */
    public TradingDays(Iterable<LocalDate> days, LocalDate coverageFrom, LocalDate coverageTo) {
        this.coverageFrom = Objects.requireNonNull(coverageFrom, "coverageFrom");
        this.coverageTo = Objects.requireNonNull(coverageTo, "coverageTo");
        TreeSet<LocalDate> copy = new TreeSet<>();
        days.forEach(copy::add);
        if (!copy.isEmpty() && (copy.first().isBefore(coverageFrom) || copy.last().isAfter(coverageTo))) {
            throw new IllegalArgumentException("거래일이 수록 범위 밖에 있습니다");
        }
        this.days = Collections.unmodifiableNavigableSet(copy);
    }

    /** 거래소 캘린더의 수록 범위 전체. */
    public static TradingDays of(ExchangeCalendar calendar) {
        return new TradingDays(calendar.tradingDays(calendar.coverageFrom(), calendar.coverageTo()),
                calendar.coverageFrom(), calendar.coverageTo());
    }

    public boolean isTradingDay(LocalDate date) {
        covered(date);
        return days.contains(date);
    }

    /** {@code date} 바로 앞 거래일. */
    public LocalDate previous(LocalDate date) {
        LocalDate d = days.lower(date);
        if (d == null) {
            throw new IllegalStateException("거래일 수록 범위 밖입니다: " + date + "의 직전 거래일");
        }
        return d;
    }

    /** {@code end}(거래일, 포함)에서 끝나는 거래일 {@code n}개, 오름차순. */
    public List<LocalDate> lastN(LocalDate end, int n) {
        if (!isTradingDay(end)) {
            throw new IllegalArgumentException("거래일이 아닙니다: " + end);
        }
        List<LocalDate> out = new ArrayList<>(n);
        out.add(end);
        LocalDate d = end;
        while (out.size() < n) {
            d = days.lower(d);
            if (d == null) {
                throw new IllegalStateException("거래일 수록 범위 밖입니다: " + end + "까지 " + n + "거래일");
            }
            out.add(d);
        }
        Collections.reverse(out);
        return out;
    }

    /** {@code date}가 든 ISO 주(월~일)의 첫 거래일. 그 주에 거래일이 없으면 예외. */
    public LocalDate firstOfIsoWeek(LocalDate date) {
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        covered(monday);
        LocalDate d = days.ceiling(monday);
        if (d == null || d.isAfter(monday.plusDays(6))) {
            throw new IllegalArgumentException("그 주에 거래일이 없습니다: " + date);
        }
        return d;
    }

    /** {@code date}가 든 ISO 주의 마지막 거래일. 그 주 일요일이 수록 범위 밖이면 예외. */
    public LocalDate lastOfIsoWeek(LocalDate date) {
        LocalDate sunday = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        covered(sunday);
        LocalDate d = days.floor(sunday);
        if (d == null || d.isBefore(sunday.minusDays(6))) {
            throw new IllegalArgumentException("그 주에 거래일이 없습니다: " + date);
        }
        return d;
    }

    private void covered(LocalDate date) {
        if (date.isBefore(coverageFrom) || date.isAfter(coverageTo)) {
            throw new IllegalStateException("거래일 수록 범위 밖입니다: " + date + " (" + coverageFrom + " ~ " + coverageTo + ")");
        }
    }
}
