package com.autotrade.sim.walkforward;

import com.autotrade.domain.Money;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.MetricsCalculator;
import com.autotrade.sim.metrics.RunMetrics;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 구간별 Run(구간마다 새 계좌, 같은 시드머니)을 하나로 이은 성과(4단계 계획 3장 마지막 문단).
 *
 * <p>자산 곡선은 구간의 일별 평가금액을 시드머니로 나눈 비율로 이어 붙인다: 구간 k의 날 d의 값은 {@code S_k × 평가금액_d / 시드},
 * {@code S_k}는 앞 구간까지 이은 값이다(구간 사이 날짜 틈에는 수익률이 없다). 지표는 {@link MetricsCalculator}를 그대로 써서
 * 이은 곡선(MDD·샤프·총수익률)과 모든 구간 왕복의 합(손익비·승률·왕복 수)에서 계산한다.
 *
 * @param complete 모든 구간이 완료됐는지. 아니면 나머지는 비어 있다
 * @param metrics 이은 지표. {@code complete}가 아니면 null
 * @param chain 이은 일별 평가금액
 * @param windowReturns 구간별 총수익률(구간 순)
 * @param windowDays 구간별 관측 거래일 수
 */
public record Pooled(boolean complete, RunMetrics metrics, List<EquityPoint> chain, List<RoundTrip> trips,
        List<BigDecimal> windowReturns, List<Integer> windowDays) {

    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);
    private static final int SCALE = 6;

    public Pooled {
        chain = List.copyOf(chain);
        trips = List.copyOf(trips);
        windowReturns = List.copyOf(windowReturns);
        windowDays = List.copyOf(windowDays);
    }

    /**
     * @param from 첫 구간 시작일, @param to 마지막 구간 종료일(CAGR 기간)
     * @param benchmarkReturn 이은 벤치마크 수익률. 없으면 null
     */
    public static Pooled of(Money seed, List<WindowRun> runs, LocalDate from, LocalDate to, BigDecimal benchmarkReturn) {
        if (runs.isEmpty() || !runs.stream().allMatch(WindowRun::completed)) {
            return new Pooled(false, null, List.of(), List.of(), List.of(), List.of());
        }
        List<EquityPoint> chain = new ArrayList<>();
        List<RoundTrip> trips = new ArrayList<>();
        List<AppliedFill> fills = new ArrayList<>();
        List<BigDecimal> returns = new ArrayList<>();
        List<Integer> days = new ArrayList<>();
        BigDecimal s = seed.amount();
        for (WindowRun r : runs) {
            for (EquityPoint p : r.equity()) {
                BigDecimal v = s.multiply(p.equity().amount()).divide(seed.amount(), MC).setScale(SCALE, RoundingMode.HALF_EVEN);
                chain.add(new EquityPoint(p.date(), p.ts(), new Money(v, seed.currency()), Money.zero(seed.currency())));
            }
            if (!r.equity().isEmpty()) {
                s = s.multiply(r.equity().get(r.equity().size() - 1).equity().amount()).divide(seed.amount(), MC)
                        .setScale(SCALE, RoundingMode.HALF_EVEN);
            }
            trips.addAll(r.roundTrips());
            fills.addAll(r.fills());
            returns.add(r.metrics().totalReturn());
            days.add(r.days());
        }
        RunMetrics m = MetricsCalculator.compute(seed, from, to, chain, fills, trips, benchmarkReturn);
        return new Pooled(true, m, chain, trips, returns, days);
    }

    /** 이은 최종 평가금액(시드머니에서 구간 수익률을 곱해 이은 값). 구간이 하나라도 실패면 null. */
    public static BigDecimal chainedFinal(Money seed, List<WindowRun> runs) {
        if (runs.isEmpty() || !runs.stream().allMatch(WindowRun::completed)) {
            return null;
        }
        BigDecimal s = seed.amount();
        for (WindowRun r : runs) {
            if (!r.equity().isEmpty()) {
                s = s.multiply(r.equity().get(r.equity().size() - 1).equity().amount()).divide(seed.amount(), MC)
                        .setScale(SCALE, RoundingMode.HALF_EVEN);
            }
        }
        return s;
    }

    /** 왕복 수 합. */
    public static int tripCount(List<WindowRun> runs) {
        return runs.stream().mapToInt(r -> r.roundTrips().size()).sum();
    }

    /**
     * 왕복당 순손익(C10) = (이은 최종 평가금액 − 시드) / 왕복 수. 비용 차감 후이고 끝까지 든 포지션의 평가손익도 들어간다.
     * 구간이 하나라도 실패했거나 왕복이 없으면 null.
     */
    public static BigDecimal perTrip(Money seed, List<WindowRun> runs) {
        BigDecimal end = chainedFinal(seed, runs);
        int n = tripCount(runs);
        return end == null || n == 0 ? null : end.subtract(seed.amount()).divide(BigDecimal.valueOf(n), MC);
    }

    /**
     * 검증 전체 비용 비중 = Σ 구간 비용 ÷ Σ 구간 비용 전 손익(구간 최종 평가금액 − 시드 + 구간 비용). 구간마다 시드에서 새로
     * 시작하므로 분자·분모를 같은 원 금액 배율(구간 합)로 맞춘다(이은 곡선의 복리 손익과 섞지 않음, PR #98 검토 M1).
     * 구간이 하나라도 실패했거나 비용 전 손익 합이 0 이하면 null.
     */
    public static BigDecimal costShare(Money seed, List<WindowRun> runs) {
        if (runs.isEmpty() || !runs.stream().allMatch(WindowRun::completed)) {
            return null;
        }
        BigDecimal costs = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        for (WindowRun r : runs) {
            BigDecimal c = r.metrics().totalCosts().amount();
            costs = costs.add(c);
            gross = gross.add(r.finalEquity().subtract(seed.amount()).add(c));
        }
        return gross.signum() <= 0 ? null : costs.divide(gross, MC).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /** 검증 전체 회전율 = 구간 회전율(매매 대금 ÷ 2 ÷ 그 구간 평균 평가금액)의 합. 구간이 하나라도 실패면 null. */
    public static BigDecimal turnover(List<WindowRun> runs) {
        if (runs.isEmpty() || !runs.stream().allMatch(WindowRun::completed)) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (WindowRun r : runs) {
            sum = sum.add(r.metrics().turnover());
        }
        return sum.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /** 이은 총수익률(복리). 완료되지 않았으면 null. */
    public BigDecimal totalReturn() {
        return metrics == null ? null : metrics.totalReturn();
    }
}
