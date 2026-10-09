package com.autotrade.sim.study;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 선발 기준 수치(STR-04, autotrade-strategy-lab 스킬 5장 기본값). Q-013(스타일별 KPI, 승격 기준 확정)이 닫히기 전까지의
 * 1차 선별 기준이며, 승격 기준을 바꾸는 값은 사용자 결정이다. 스킬 기본값에 없는 값은 `[가정]`이다.
 *
 * @param minProfitFactor 검증 구간 Profit Factor 하한(스킬 기본 1.3)
 * @param maxDrawdown 검증 구간 MDD 상한(스킬 기본 20%)
 * @param minSharpe 검증 구간 샤프 하한(스킬 기본 1.0)
 * @param minRoundTrips 검증 구간 왕복 거래 수 하한(스킬 기본 30)
 * @param minOosRatio 검증 구간 하루 평균 수익률 / 학습 구간 하루 평균 수익률 하한(스킬 기본 0.6)
 * @param minPerturbedRatio 핵심 파라미터 ±20% 변형의 학습 구간 수익률 / 원래 수익률 하한 `[가정]` 0.5("급락 없음"의 수치화)
 * @param minPerturbedProfitFactor 변형의 학습 구간 PF 하한 `[가정]` 1.0
 * @param stressLatency 지연 민감도 판정 지연(STR-13 최대값 2초)
 * @param minStressProfitFactor 그 지연에서 검증 구간 PF 하한 `[가정]` 1.0(수익률도 0보다 커야 함)
 * @param nullPercentile 무작위 진입 대조군 수익률 분포에서 넘어야 할 분위 `[가정]` 0.95
 */
public record ScreeningThresholds(
        BigDecimal minProfitFactor,
        BigDecimal maxDrawdown,
        BigDecimal minSharpe,
        int minRoundTrips,
        BigDecimal minOosRatio,
        BigDecimal minPerturbedRatio,
        BigDecimal minPerturbedProfitFactor,
        Duration stressLatency,
        BigDecimal minStressProfitFactor,
        BigDecimal nullPercentile) {

    public static ScreeningThresholds defaults() {
        return new ScreeningThresholds(
                new BigDecimal("1.3"),
                new BigDecimal("0.20"),
                new BigDecimal("1.0"),
                30,
                new BigDecimal("0.6"),
                new BigDecimal("0.5"),
                new BigDecimal("1.0"),
                Duration.ofSeconds(2),
                new BigDecimal("1.0"),
                new BigDecimal("0.95"));
    }
}
