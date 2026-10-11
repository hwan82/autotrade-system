package com.autotrade.selection.calc;

import java.math.BigDecimal;

/** 자격 조건 비교 연산(STR-16). 경계값은 GE·LE가 포함, GT·LT가 제외한다. */
public enum CompareOp {
    GE, GT, LE, LT;

    public boolean test(BigDecimal value, BigDecimal threshold) {
        int c = value.compareTo(threshold);
        return switch (this) {
            case GE -> c >= 0;
            case GT -> c > 0;
            case LE -> c <= 0;
            case LT -> c < 0;
        };
    }
}
