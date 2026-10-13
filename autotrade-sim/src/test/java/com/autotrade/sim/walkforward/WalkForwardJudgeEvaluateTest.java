package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.MetricsCalculator;
import com.autotrade.sim.study.Check;
import com.autotrade.sim.study.Check.Outcome;
import com.autotrade.sim.study.ScreeningThresholds;
import com.autotrade.sim.walkforward.WalkForwardJudge.Evidence;
import com.autotrade.sim.walkforward.WalkForwardJudge.Judgement;
import com.autotrade.sim.walkforward.WalkForwardJudge.Verdict;
import com.autotrade.strategy.catalog.EntryFunnel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 판정식 손 계산(PR #79 strategy-researcher 검토 M2): 합성 {@link WindowRun}으로 {@link WalkForwardJudge#evaluate}가 내는
 * C1·C1w·C6·C7·C8·C9d·C10 값과 {@link Pooled}의 이은 곡선·왕복당 순손익을 손으로 계산한 값과 맞춘다. 시드 1,000,000원.
 *
 * <p>검증 구간 3개(평가금액, 원):
 * <pre>
 * V1 2023-01-02 1,010,000 · 2023-01-03 1,030,000   수익률 3%   왕복 +40,000, −10,000
 * V2 2024-01-02 1,020,000 · 2024-02-01 1,050,000   수익률 5%   왕복 +50,000
 * V3 2025-01-02   990,000 · 2025-03-03 1,020,000   수익률 2%   왕복 +20,000(구간 끝 정리)
 * </pre>
 * 이은 곡선: 1,010,000 → 1,030,000 → 1,050,600 → 1,081,500 → 1,070,685 → 1,103,130 (구간 k 값 = 앞 구간까지 이은 값 × 평가금액 / 시드).
 */
class WalkForwardJudgeEvaluateTest {

    private static final Money SEED = Money.of(1_000_000, Money.KRW);
    private static final LocalDate FROM = LocalDate.of(2023, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 31);
    private static final Symbol S = Symbol.krx("005930");
    private static int lot;

    private static WindowRun run(int window, Object... dateEquityThenTrips) {
        List<EquityPoint> eq = new ArrayList<>();
        List<RoundTrip> trips = new ArrayList<>();
        int i = 0;
        while (i < dateEquityThenTrips.length && dateEquityThenTrips[i] instanceof String d) {
            LocalDate date = LocalDate.parse(d);
            long v = (Long) dateEquityThenTrips[i + 1];
            eq.add(new EquityPoint(date, date.atTime(15, 30).toInstant(ZoneOffset.ofHours(9)), Money.of(v, Money.KRW),
                    Money.of(v, Money.KRW)));
            i += 2;
        }
        for (; i < dateEquityThenTrips.length; i++) {
            long pnl = (Long) dateEquityThenTrips[i];
            LocalDate d = eq.get(eq.size() - 1).date();
            trips.add(new RoundTrip("L" + (++lot), S, eq.get(0).date(), d, Money.of(pnl, Money.KRW)));
        }
        var m = MetricsCalculator.compute(SEED, eq.get(0).date(), eq.get(eq.size() - 1).date(), eq, List.of(), trips, null);
        return new WindowRun(window, "", m, eq, trips, List.of(), Set.of(), EntryFunnel.Totals.ZERO);
    }

    private static List<WindowRun> val() {
        return List.of(
                run(1, "2023-01-02", 1_010_000L, "2023-01-03", 1_030_000L, 40_000L, -10_000L),
                run(2, "2024-01-02", 1_020_000L, "2024-02-01", 1_050_000L, 50_000L),
                run(3, "2025-01-02", 990_000L, "2025-03-03", 1_020_000L, 20_000L));
    }

    /** 학습 구간: 세 구간 모두 1,005,000 → 1,010,000(수익률 1%, 일수익률 0.5%, 0.4975%). */
    private static List<WindowRun> train(long first, long second, long... trips) {
        List<WindowRun> out = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            Object[] a = new Object[4 + trips.length];
            a[0] = (2019 + k) + "-01-02";
            a[1] = first;
            a[2] = (2019 + k) + "-06-01";
            a[3] = second;
            for (int j = 0; j < trips.length; j++) {
                a[4 + j] = trips[j];
            }
            out.add(run(k, a));
        }
        return out;
    }

    private static List<BigDecimal> nulls(int n) {
        List<BigDecimal> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            l.add(BigDecimal.valueOf(i * 100L));
        }
        return l;
    }

    private static ScreeningThresholds thresholds(int minTrips) {
        ScreeningThresholds d = ScreeningThresholds.defaults();
        return new ScreeningThresholds(d.minProfitFactor(), d.maxDrawdown(), d.minSharpe(), minTrips, d.minOosRatio(),
                d.minPerturbedRatio(), d.minPerturbedProfitFactor(), Duration.ofSeconds(2), d.minStressProfitFactor(),
                d.nullPercentile());
    }

    private static Check check(Judgement j, String code) {
        return j.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    private static Evidence evidence(List<WindowRun> train, List<WindowRun> low, List<WindowRun> high,
            List<WindowRun> stress, List<BigDecimal> idxWindows) {
        return new Evidence(val(), stress, train, low, high, "", "signal.k", nulls(19), idxWindows, new BigDecimal("0.10"));
    }

    @Test
    void 이은_곡선과_왕복당_순손익은_손_계산과_같다() {
        Pooled p = Pooled.of(SEED, val(), FROM, TO, null);
        assertThat(p.chain()).extracting(e -> e.equity().amount()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1010000"), new BigDecimal("1030000"), new BigDecimal("1050600"),
                        new BigDecimal("1081500"), new BigDecimal("1070685"), new BigDecimal("1103130"));
        assertThat(Pooled.chainedFinal(SEED, val())).isEqualByComparingTo("1103130");
        assertThat(p.totalReturn()).isEqualByComparingTo("0.10313");
        // (1,103,130 − 1,000,000) / 왕복 4 = 25,782.5
        assertThat(Pooled.perTrip(SEED, val())).isEqualByComparingTo("25782.5");
        // PF = 이익 110,000 / 손실 10,000, MDD = (1,081,500 − 1,070,685) / 1,081,500 = 1%
        assertThat(p.metrics().profitFactor()).isEqualByComparingTo("11");
        assertThat(p.metrics().maxDrawdown()).isEqualByComparingTo("0.01");
        assertThat(p.windowReturns()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.03"), new BigDecimal("0.05"), new BigDecimal("0.02"));
    }

    @Test
    void 판정_항목별_값이_손_계산과_같다() {
        List<WindowRun> tr = train(1_005_000L, 1_010_000L, 10_000L);
        // 변형: 낮은 쪽 0.5% = 원래 1% × 0.5(경계 통과, PF 100/100 = 1.0 경계 통과)
        List<WindowRun> low = train(1_002_000L, 1_005_000L, 100L, -100L);
        List<WindowRun> high = train(1_005_000L, 1_010_000L, 10_000L);
        Judgement j = WalkForwardJudge.evaluate(evidence(tr, low, high, val(), List.of(new BigDecimal("0.02"),
                new BigDecimal("0.04"), new BigDecimal("0.01"))), 1, SEED, thresholds(4), FROM, TO);

        // C1: 10.313% − 지수 10% > 0
        assertThat(check(j, "C1").outcome()).isEqualTo(Outcome.PASS);
        assertThat(check(j, "C1").detail()).contains("초과 0.31");
        // C1w: 초과 1%, 1%, 1% → 3/3
        assertThat(check(j, "C1w").outcome()).isEqualTo(Outcome.PASS);
        assertThat(check(j, "C1w").detail()).startsWith("3/3");
        assertThat(check(j, "C2").outcome()).isEqualTo(Outcome.PASS);
        assertThat(check(j, "C3").outcome()).isEqualTo(Outcome.PASS);
        // C6: 검증 일수익률 평균 0.0165861… ÷ 학습 0.0049875… = 3.3255 → 3.33
        assertThat(check(j, "C6").outcome()).isEqualTo(Outcome.PASS);
        assertThat(check(j, "C6").detail()).contains("비 3.33");
        // C7: 낮은 변형 0.5% ≥ 1% × 0.5, PF 1.0 ≥ 1.0 → 통과
        assertThat(check(j, "C7").outcome()).isEqualTo(Outcome.PASS);
        // C8: 달별 손익 2023-01 +30,000, 2024-01 +20,600, 2024-02 +30,900, 2025-01 −10,815, 2025-03 +32,445.
        //     최고 달 2025-03을 빼면 70,685 > 0
        assertThat(check(j, "C8").outcome()).isEqualTo(Outcome.PASS);
        assertThat(check(j, "C8").detail()).contains("2025-03");
        assertThat(check(j, "C9d").outcome()).isEqualTo(Outcome.PASS);
        // C10: 25,782.5 > 대조군 최대 1,900 → k=0, p = 1/20 = 0.05 ≤ 0.05/1
        assertThat(check(j, "C10").outcome()).isEqualTo(Outcome.PASS);
        assertThat(j.pValue()).isEqualByComparingTo("0.05");
        assertThat(j.perTrip()).isEqualByComparingTo("25782.5");
        // C4: 이은 곡선 관측일 6일 < 샤프 최소 20일(D-069) → 판정 불가, 그래서 다른 항목이 모두 통과해도 보류
        assertThat(check(j, "C4").outcome()).isEqualTo(Outcome.NOT_AVAILABLE);
        assertThat(j.checks()).filteredOn(c -> !c.code().equals("C4")).as(j.checks().toString())
                .allSatisfy(c -> assertThat(c.outcome()).isEqualTo(Outcome.PASS));
        assertThat(j.verdict()).isEqualTo(Verdict.HOLD);
    }

    @Test
    void 경계_바로_밖이면_해당_항목만_탈락한다() {
        List<WindowRun> tr = train(1_005_000L, 1_010_000L, 10_000L);
        // C7: 낮은 변형 0.4999% < 0.5% → 탈락
        List<WindowRun> low = train(1_002_000L, 1_004_999L, 100L, -100L);
        // C9d: 수익은 나지만 PF 100/101 < 1.0 → 탈락
        List<WindowRun> stress = List.of(run(1, "2023-01-02", 1_001_000L, 100L, -101L),
                run(2, "2024-01-02", 1_001_000L), run(3, "2025-01-02", 1_001_000L));
        // C1w: 구간 2 초과 −0.0001%만으로 2/3 → 탈락(3구간이면 3구간 모두 필요)
        Judgement j = WalkForwardJudge.evaluate(evidence(tr, low, tr, stress, List.of(new BigDecimal("0.02"),
                new BigDecimal("0.050001"), new BigDecimal("0.01"))), 1, SEED, thresholds(4), FROM, TO);
        assertThat(check(j, "C7").outcome()).isEqualTo(Outcome.FAIL);
        assertThat(check(j, "C9d").outcome()).isEqualTo(Outcome.FAIL);
        assertThat(check(j, "C1w").outcome()).isEqualTo(Outcome.FAIL);
        assertThat(check(j, "C1w").detail()).startsWith("2/3");
        assertThat(check(j, "C1").outcome()).isEqualTo(Outcome.PASS);
        assertThat(j.verdict()).isEqualTo(Verdict.FAIL);
        // 대조군 최대값과 같으면 같은 값도 이상으로 세어 k=1 → p = 2/20 = 0.1 > 0.05 → C10 탈락
        List<BigDecimal> withTie = new ArrayList<>(nulls(18));
        withTie.add(new BigDecimal("25782.5"));
        List<BigDecimal> zero = List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Judgement t = WalkForwardJudge.evaluate(new Evidence(val(), val(), tr, tr, tr, "", "signal.k", withTie, zero,
                BigDecimal.ZERO), 1, SEED, thresholds(4), FROM, TO);
        assertThat(check(t, "C10").outcome()).isEqualTo(Outcome.FAIL);
        assertThat(t.pValue()).isEqualByComparingTo("0.1");
    }

    @Test
    void C6는_검증이_학습의_정확히_60퍼센트면_통과하고_조금_모자라면_탈락한다() {
        // 학습 일수익률 1%(1,010,000), 검증 0.6%(1,006,000) → 비 0.60
        List<WindowRun> tr = List.of(run(1, "2022-01-03", 1_010_000L, 10_000L));
        List<WindowRun> ok = List.of(run(1, "2023-01-02", 1_006_000L, 6_000L));
        List<WindowRun> bad = List.of(run(1, "2023-01-02", 1_005_999L, 5_999L));
        List<BigDecimal> idx = List.of(BigDecimal.ZERO);
        Judgement a = WalkForwardJudge.evaluate(new Evidence(ok, ok, tr, null, null, "없음", "", nulls(19), idx,
                BigDecimal.ZERO), 1, SEED, thresholds(1), FROM, TO);
        Judgement b = WalkForwardJudge.evaluate(new Evidence(bad, bad, tr, null, null, "없음", "", nulls(19), idx,
                BigDecimal.ZERO), 1, SEED, thresholds(1), FROM, TO);
        assertThat(check(a, "C6").outcome()).isEqualTo(Outcome.PASS);
        assertThat(check(a, "C6").detail()).contains("비 0.60");
        assertThat(check(b, "C6").outcome()).isEqualTo(Outcome.FAIL);
        assertThat(check(a, "C7").outcome()).as("변형 없음 → 판정 불가").isEqualTo(Outcome.NOT_AVAILABLE);
    }
}
