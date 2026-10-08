package com.autotrade.sim.study;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 비교 실험 묶음의 결과.
 *
 * @param grid 학습·검증 × 지연 실험
 * @param perturbation 파라미터 ±20% 변형 실험(학습 구간). 변형할 프로필이 없으면 null
 * @param perturbations 원래 프로필 ID → 변형 정보
 * @param nullRuns 무작위 진입 대조군을 시드만 바꿔 돌린 검증 구간 실험
 * @param deterministic 같은 실험을 병렬 수만 바꿔 다시 돌린 정규 텍스트가 같았는지
 * @param screenings 프로필 목록 순서
 */
public record StudyResult(
        StudyConfig config,
        String dataVersion,
        List<LocalDate> tradingDays,
        List<ExperimentOutcome> grid,
        ExperimentOutcome perturbation,
        Map<String, StrategyStudy.PerturbedPair> perturbations,
        List<ExperimentOutcome> nullRuns,
        boolean deterministic,
        List<Screening> screenings) {

    public StudyResult {
        tradingDays = List.copyOf(tradingDays);
        grid = List.copyOf(grid);
        perturbations = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(perturbations));
        nullRuns = List.copyOf(nullRuns);
        screenings = List.copyOf(screenings);
    }

    /** 학습({@code TRAIN})·검증({@code VALIDATION}) 구간의 해당 지연 실험. */
    public ExperimentOutcome outcome(String period, Duration latency) {
        return grid.stream().filter(o -> o.period().equals(period) && o.latency().equals(latency)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("실험이 없습니다: " + period + " " + latency));
    }
}
