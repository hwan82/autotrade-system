package com.autotrade.sim.cost;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 적용 시작일이 있는 비용 표(D-021 2항, Q-004 조사 4.2). 만들 때 구간 규칙을 검사해 어기면 예외를 던진다:
 * {@code from} 순 정렬, 겹침 없음, 빈틈 없음, 열린 구간은 마지막 하나뿐. 불변이고 스레드 안전하다.
 */
public final class PeriodTable<V extends CostValue> {

    private final List<Period<V>> periods;

    public PeriodTable(List<Period<V>> periods) {
        this.periods = List.copyOf(periods);
        if (this.periods.isEmpty()) {
            throw new IllegalArgumentException("비용 표에 구간이 하나 이상 필요합니다");
        }
        for (int i = 0; i < this.periods.size(); i++) {
            Period<V> p = this.periods.get(i);
            boolean last = i == this.periods.size() - 1;
            if (p.to() == null && !last) {
                throw new IllegalArgumentException("열린 구간은 마지막 하나만 허용됩니다: " + p.from());
            }
            if (!last) {
                Period<V> next = this.periods.get(i + 1);
                LocalDate expected = p.to().plusDays(1);
                if (next.from().isBefore(expected)) {
                    throw new IllegalArgumentException("비용 표 구간이 겹칩니다: " + p.to() + " / " + next.from());
                }
                if (next.from().isAfter(expected)) {
                    throw new IllegalArgumentException("비용 표 구간에 빈틈이 있습니다: " + p.to() + " ~ " + next.from());
                }
            }
        }
    }

    @SafeVarargs
    public static <V extends CostValue> PeriodTable<V> of(Period<V>... periods) {
        List<Period<V>> list = new ArrayList<>(periods.length);
        for (Period<V> p : periods) {
            list.add(p);
        }
        return new PeriodTable<>(list);
    }

    /** 이 날짜에 적용되는 값. 표 밖이면 {@link IllegalStateException}(실험 시작 검사로 미리 막아야 하는 경우). */
    public V at(LocalDate date) {
        Objects.requireNonNull(date, "date");
        for (Period<V> p : periods) {
            if (p.contains(date)) {
                return p.value();
            }
        }
        throw new IllegalStateException("비용 표가 이 날짜를 덮지 않습니다: " + date);
    }

    /** [start, end] 전체가 표 안에 있는지. end가 null이면 start부터 열린 기간(실시간 모의). */
    public boolean covers(LocalDate start, LocalDate end) {
        Period<V> first = periods.get(0);
        Period<V> last = periods.get(periods.size() - 1);
        if (start.isBefore(first.from())) {
            return false;
        }
        if (last.to() == null) {
            return true;
        }
        return end != null && !end.isAfter(last.to());
    }

    /** [start, end]와 겹치는 구간만. 비용 표 해시에 쓴다(미래 구간이 추가돼도 과거 실험의 해시가 그대로이도록). */
    public List<Period<V>> overlapping(LocalDate start, LocalDate end) {
        List<Period<V>> out = new ArrayList<>();
        for (Period<V> p : periods) {
            if (p.overlaps(start, end)) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    public List<Period<V>> periods() {
        return periods;
    }
}
