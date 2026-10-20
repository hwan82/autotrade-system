package com.autotrade.selection.calc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 자격 조건 하나: 지표 × 비교 연산 × 기준값 × 기간(STR-16, 프로필 키 {@code gate.<nn>.*}).
 *
 * @param id {@code gate.01}처럼 프로필 키 접두어. 평가 값과 사유 코드({@code GATE_01})에 쓴다
 * @param period 지표의 기간(거래일 수 또는 사업연도 수). 기간이 없는 지표는 0
 * @param types 공시 유형({@code gate.<nn>.types}, {@code disclosure-count}만). 다른 지표는 빈 집합
 */
public record Condition(String id, Metric metric, CompareOp op, BigDecimal threshold, int period, SortedSet<String> types) {

    public Condition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(threshold, "threshold");
        types = Collections.unmodifiableSortedSet(new TreeSet<>(types));
        if ((metric == Metric.DISCLOSURE_COUNT) == types.isEmpty()) {
            throw new IllegalArgumentException(id + ": 공시 유형은 disclosure-count에만, 그리고 반드시 줍니다");
        }
    }

    public Condition(String id, Metric metric, CompareOp op, BigDecimal threshold, int period) {
        this(id, metric, op, threshold, period, new TreeSet<>());
    }

    /** 이 조건의 사유 코드 접두어({@code gate.01} → {@code GATE_01}). */
    public String reasonCode() {
        return id.toUpperCase(java.util.Locale.ROOT).replace('.', '_');
    }
}
