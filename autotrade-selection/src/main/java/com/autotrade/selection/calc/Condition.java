package com.autotrade.selection.calc;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 자격 조건 하나: 지표 × 비교 연산 × 기준값 × 기간(STR-16, 프로필 키 {@code gate.<nn>.*}).
 *
 * @param id {@code gate.01}처럼 프로필 키 접두어. 평가 값과 사유 코드({@code GATE_01})에 쓴다
 * @param period 지표의 기간(거래일 수 또는 사업연도 수). 기간이 없는 지표는 0
 */
public record Condition(String id, Metric metric, CompareOp op, BigDecimal threshold, int period) {

    public Condition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(threshold, "threshold");
    }

    /** 이 조건의 사유 코드 접두어({@code gate.01} → {@code GATE_01}). */
    public String reasonCode() {
        return id.toUpperCase(java.util.Locale.ROOT).replace('.', '_');
    }
}
