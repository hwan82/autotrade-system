package com.autotrade.sim.metrics;

import com.autotrade.domain.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Run 하나의 성과 지표(SIM-11). 비율은 소수(0.05 = 5%)이며 소수 6자리 HALF_EVEN으로 맞춘다.
 * 정의할 수 없는 지표(예: 손실 거래가 없을 때 손익비)는 null이다. 정의는 ADR-004 2.5.
 *
 * @param totalReturn 최종 평가금액 / 시드 − 1
 * @param cagr 연환산 수익률. 기간은 실험 시작일~종료일 달력 일수 / 365. 관측 거래일이 {@link MetricsCalculator#MIN_CAGR_DAYS}
 *     미만이면 null(판정 불가, D-069)
 * @param maxDrawdown 일별 평가금액의 고점 대비 최대 하락률(양수)
 * @param sharpe 일별 수익률 평균 / 표본 표준편차 × √252, 무위험 수익률 0 `[가정]`. 관측 거래일이
 *     {@link MetricsCalculator#MIN_SHARPE_DAYS} 미만이거나(판정 불가, D-069) 변동이 없으면 null
 * @param winRate 수익 왕복 거래 수 / 왕복 거래 수
 * @param profitFactor 수익 왕복 손익 합 / |손실 왕복 손익 합|
 * @param avgHoldingDays 왕복 거래의 진입일~청산일 달력 일수 평균(당일 청산은 0)
 * @param fillCount 체결 건수
 * @param roundTripCount 왕복 거래 수(청산 완료분만)
 * @param turnover (매수 금액 + 매도 금액) / 2 / 평균 일별 평가금액
 * @param totalCosts 수수료 + 세금·규제 수수료 합계. 슬리피지는 체결가에 이미 들어 있어 빼고 센다
 * @param costShare 총비용 / (순손익 + 총비용). 비용 전 손익이 0 이하면 null
 * @param benchmarkReturn 벤치마크 매수 후 보유 수익률(비용 제외). 벤치마크가 없으면 null
 * @param excessReturn totalReturn − benchmarkReturn
 */
public record RunMetrics(
        Money finalEquity,
        BigDecimal totalReturn,
        BigDecimal cagr,
        BigDecimal maxDrawdown,
        BigDecimal sharpe,
        BigDecimal winRate,
        BigDecimal profitFactor,
        BigDecimal avgHoldingDays,
        int fillCount,
        int roundTripCount,
        BigDecimal turnover,
        Money totalCosts,
        BigDecimal costShare,
        BigDecimal benchmarkReturn,
        BigDecimal excessReturn) {

    public RunMetrics {
        Objects.requireNonNull(finalEquity, "finalEquity");
        Objects.requireNonNull(totalReturn, "totalReturn");
        Objects.requireNonNull(maxDrawdown, "maxDrawdown");
        Objects.requireNonNull(totalCosts, "totalCosts");
    }
}
