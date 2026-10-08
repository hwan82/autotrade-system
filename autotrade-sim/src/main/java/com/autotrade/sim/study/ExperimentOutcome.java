package com.autotrade.sim.study;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * 실험 하나(같은 기간·시드·비용·체결 모델)의 결과. 이 안의 Run끼리만 직접 비교한다(프로젝트 지침 5장).
 *
 * @param period {@code TRAIN}, {@code VALIDATION}, {@code PERTURB}, {@code NULL-<n>} 등
 * @param runs 프로필 ID → 요약. 프로필 목록 순서
 */
public record ExperimentOutcome(
        String experimentId,
        String period,
        LocalDate from,
        LocalDate to,
        int tradingDays,
        long randomSeed,
        Duration latency,
        String executionModelId,
        String costTableHash,
        Map<String, RunSummary> runs) {

    public RunSummary run(String profileId) {
        return runs.get(profileId);
    }
}
