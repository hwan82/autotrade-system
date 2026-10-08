package com.autotrade.sim.study;

import java.util.List;

/**
 * 프로필 하나의 선발 기준 판정. 모든 항목이 PASS여야 승격 "후보"다(승격 자체는 STR-05 순서와 사용자 결정).
 *
 * @param reference 기준선·대조군이면 true(후보 판정 대상이 아님)
 */
public record Screening(String profileId, boolean reference, List<Check> checks) {

    public Screening {
        checks = List.copyOf(checks);
    }

    public boolean candidate() {
        return !reference && !checks.isEmpty() && checks.stream().allMatch(Check::passed);
    }

    public long passedCount() {
        return checks.stream().filter(Check::passed).count();
    }
}
