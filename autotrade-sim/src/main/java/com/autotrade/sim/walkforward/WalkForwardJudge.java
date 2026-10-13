package com.autotrade.sim.walkforward;

import com.autotrade.domain.Money;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.study.Check;
import com.autotrade.sim.study.ScreeningCriteria;
import com.autotrade.sim.study.ScreeningThresholds;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 일봉 워크포워드 선발 판정(4단계 계획 4장, D-071 5번). 모든 값은 비용 차감 후이고, 수치는 {@link ScreeningThresholds}의
 * 기본값(D-027·D-066)이다. 기존 {@link ScreeningCriteria}의 판정 도구(PF·문턱 비교·분위수)를 그대로 쓴다.
 *
 * <table>
 *   <caption>판정 항목</caption>
 *   <tr><th>코드</th><th>기준</th><th>구간</th></tr>
 *   <tr><td>C1</td><td>시총 가중 지수 대비 초과수익 &gt; 0</td><td>검증 전체(이은 곡선)</td></tr>
 *   <tr><td>C1w</td><td>구간의 3/4 이상(올림, 3구간이면 모두)에서 지수 초과</td><td>구간별 검증</td></tr>
 *   <tr><td>C2</td><td>Profit Factor ≥ 1.3</td><td>검증 전체</td></tr>
 *   <tr><td>C3</td><td>MDD ≤ 20%</td><td>이은 곡선</td></tr>
 *   <tr><td>C4</td><td>샤프 ≥ 1.0</td><td>이은 곡선</td></tr>
 *   <tr><td>C5</td><td>왕복 ≥ 30. 미달이면 조합 "판정 불가"(보류)</td><td>검증 합</td></tr>
 *   <tr><td>C6</td><td>(모든 검증 구간 일수익률 평균) ÷ (모든 학습 구간 일수익률 평균) ≥ 60%(학습 평균 ≤ 0이면 탈락, 예열 제외)</td><td>학습·검증</td></tr>
 *   <tr><td>C7</td><td>핵심 파라미터 ±20% 변형의 학습 수익률(구간 평균)이 원래의 50% 이상, 이은 PF ≥ 1.0</td><td>학습</td></tr>
 *   <tr><td>C8</td><td>가장 좋은 한 달을 빼도 수익</td><td>검증 전체</td></tr>
 *   <tr><td>C9d</td><td>슬리피지 2배에서도 수익, PF ≥ 1.0</td><td>검증 전체</td></tr>
 *   <tr><td>C10</td><td>왕복당 순손익의 경험적 p = (k+1)/(N+1) ≤ 0.05/m(k: 후보 값 이상인 대조군 수). 1/(N+1) &gt; 0.05/m이면 판정 불가</td><td>검증 전체</td></tr>
 * </table>
 *
 * <p>결과: C5 미달이면 다른 항목과 무관하게 보류, 아니면 탈락(FAIL)이 하나라도 있으면 탈락, 판정 불가가 있으면 보류, 전부 통과면 통과.
 */
public final class WalkForwardJudge {

    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);
    private static final BigDecimal FAMILY_ALPHA = new BigDecimal("0.05");

    private WalkForwardJudge() {
    }

    public enum Verdict {
        PASS("통과"),
        FAIL("탈락"),
        HOLD("보류");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 한 후보 조합의 판정 근거.
     *
     * @param val 구간별 검증 Run(기본 비용), @param stress 슬리피지 2배 검증 Run, @param train 구간별 학습 Run
     * @param low ±20% 중 낮은 변형의 구간별 학습 Run, @param high 높은 변형. 변형이 없으면 null(C7 판정 불가), 이유는
     *     {@code perturbNote}
     * @param nullPerTrip 같은 선정 + 대조군의 시드별 왕복당 순손익(오름차순, 완료된 것만)
     * @param idxCapWindows 구간별 검증 기간 지수(시총 가중) 수익률, @param idxCapPooled 이은 지수 수익률
     */
    public record Evidence(List<WindowRun> val, List<WindowRun> stress, List<WindowRun> train, List<WindowRun> low,
            List<WindowRun> high, String perturbNote, String perturbKey, List<BigDecimal> nullPerTrip,
            List<BigDecimal> idxCapWindows, BigDecimal idxCapPooled) {
    }

    /**
     * @param validation 이은 검증 성과(실패 구간이 있으면 {@code complete=false})
     * @param stress 슬리피지 2배 이은 성과
     * @param perTrip 검증 왕복당 순손익(원, 왕복 0이면 null)
     * @param pValue C10 경험적 p(판정 불가면 null)
     */
    public record Judgement(Verdict verdict, List<Check> checks, Pooled validation, Pooled stress,
            BigDecimal perTrip, BigDecimal pValue) {

        public Judgement {
            checks = List.copyOf(checks);
        }
    }

    /** 구간 3/4 이상(올림) 기준의 최소 구간 수. 3구간이면 3, 4구간이면 3. */
    public static int requiredWindows(int windows) {
        return (3 * windows + 3) / 4;
    }

    /** 본페로니 분위 q = 1 − 0.05 / m. */
    public static BigDecimal nullQuantile(int m) {
        return BigDecimal.ONE.subtract(FAMILY_ALPHA.divide(BigDecimal.valueOf(m), MC));
    }

    public static Judgement evaluate(Evidence ev, int m, Money seed, ScreeningThresholds t, java.time.LocalDate from,
            java.time.LocalDate to) {
        Pooled val = Pooled.of(seed, ev.val(), from, to, ev.idxCapPooled());
        Pooled stress = Pooled.of(seed, ev.stress(), from, to, null);
        List<Check> checks = new ArrayList<>();
        if (!val.complete()) {
            String why = ev.val().stream().filter(r -> !r.completed()).map(r -> "구간 " + r.window() + ": " + r.failure())
                    .findFirst().orElse("검증 Run 없음");
            checks.add(new Check("C0", "Run 완료", Check.Outcome.NOT_AVAILABLE, "검증 구간 Run 실패: " + why));
            return new Judgement(Verdict.HOLD, checks, val, stress, null, null);
        }
        RunMetrics vm = val.metrics();
        BigDecimal perTrip = Pooled.perTrip(seed, ev.val());
        checks.add(c1(vm, ev.idxCapPooled()));
        checks.add(c1w(val, ev.idxCapWindows()));
        checks.add(ScreeningCriteria.profitFactor("C2", "Profit Factor ≥ " + t.minProfitFactor(), vm, t.minProfitFactor()));
        checks.add(ScreeningCriteria.threshold("C3", "MDD ≤ " + ScreeningCriteria.pct(t.maxDrawdown()), vm.maxDrawdown(),
                t.maxDrawdown(), false, ScreeningCriteria.pct(vm.maxDrawdown())));
        checks.add(ScreeningCriteria.threshold("C4", "샤프 ≥ " + t.minSharpe(), vm.sharpe(), t.minSharpe(), true,
                ScreeningCriteria.num(vm.sharpe())));
        Check c5 = vm.roundTripCount() >= t.minRoundTrips()
                ? new Check("C5", "검증 왕복 합 ≥ " + t.minRoundTrips(), Check.Outcome.PASS, vm.roundTripCount() + "건")
                : new Check("C5", "검증 왕복 합 ≥ " + t.minRoundTrips(), Check.Outcome.NOT_AVAILABLE,
                        vm.roundTripCount() + "건 — 표본이 모자라 판정 불가(D-066 2번)");
        checks.add(c5);
        checks.add(c6(ev.train(), ev.val(), seed, t));
        checks.add(c7(ev, seed, t, from, to));
        checks.add(c8(val, seed));
        checks.add(c9d(stress, t));
        checks.add(c10(perTrip, ev.nullPerTrip(), m));
        BigDecimal pValue = pValue(perTrip, ev.nullPerTrip(), m);
        return new Judgement(verdict(checks), checks, val, stress, perTrip, pValue);
    }

    /** C5 미달 → 보류. 아니면 탈락이 있으면 탈락, 판정 불가가 있으면 보류, 모두 통과면 통과. */
    public static Verdict verdict(List<Check> checks) {
        for (Check c : checks) {
            if (c.code().equals("C5") && c.outcome() != Check.Outcome.PASS) {
                return Verdict.HOLD;
            }
        }
        if (checks.stream().anyMatch(c -> c.outcome() == Check.Outcome.FAIL)) {
            return Verdict.FAIL;
        }
        if (checks.stream().anyMatch(c -> c.outcome() == Check.Outcome.NOT_AVAILABLE)) {
            return Verdict.HOLD;
        }
        return Verdict.PASS;
    }

    private static Check c1(RunMetrics vm, BigDecimal idx) {
        String title = "시총 가중 지수 대비 초과수익 > 0";
        BigDecimal excess = vm.totalReturn().subtract(idx);
        return new Check("C1", title, excess.signum() > 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "계좌 " + ScreeningCriteria.pct(vm.totalReturn()) + ", 지수 " + ScreeningCriteria.pct(idx) + ", 초과 "
                        + ScreeningCriteria.pct(excess));
    }

    private static Check c1w(Pooled val, List<BigDecimal> idxWindows) {
        int w = val.windowReturns().size();
        int need = requiredWindows(w);
        String title = "구간 " + need + "/" + w + " 이상에서 지수 초과";
        int beat = 0;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < w; k++) {
            BigDecimal ex = val.windowReturns().get(k).subtract(idxWindows.get(k));
            if (ex.signum() > 0) {
                beat++;
            }
            sb.append(k == 0 ? "" : ", ").append("W").append(k + 1).append(' ').append(ScreeningCriteria.pct(ex));
        }
        return new Check("C1w", title, beat >= need ? Check.Outcome.PASS : Check.Outcome.FAIL,
                beat + "/" + w + " 구간 초과 (" + sb + ")");
    }

    private static Check c6(List<WindowRun> train, List<WindowRun> val, Money seed, ScreeningThresholds t) {
        String title = "검증/학습 하루 수익률 ≥ " + t.minOosRatio();
        if (train.isEmpty() || !train.stream().allMatch(WindowRun::completed) || train.size() != val.size()) {
            return new Check("C6", title, Check.Outcome.NOT_AVAILABLE, "학습 구간 Run 없음 또는 실패");
        }
        BigDecimal trainDaily = meanDaily(train, seed);
        BigDecimal valDaily = meanDaily(val, seed);
        if (trainDaily == null || valDaily == null) {
            return new Check("C6", title, Check.Outcome.NOT_AVAILABLE, "관측 거래일이 없음");
        }
        String detail = "학습 " + ScreeningCriteria.bp(trainDaily) + "/일, 검증 " + ScreeningCriteria.bp(valDaily)
                + "/일 (모든 구간의 일수익률 평균, 예열 제외, 학습 구간끼리 겹치는 날은 구간마다 따로 셈)";
        if (trainDaily.signum() <= 0) {
            return new Check("C6", title, Check.Outcome.FAIL, detail + " (학습 평균 ≤ 0)");
        }
        BigDecimal ratio = valDaily.divide(trainDaily, MC);
        return new Check("C6", title, ratio.compareTo(t.minOosRatio()) >= 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                detail + ", 비 " + ratio.setScale(2, RoundingMode.HALF_EVEN).toPlainString());
    }

    /**
     * 모든 구간의 일수익률(그날 평가금액 / 전날 평가금액 − 1, 구간 첫날은 시드 대비) 평균. 구간 밖 예열 일은 자산 곡선에 없어
     * 들어가지 않는다. 관측 거래일이 없으면 null.
     */
    private static BigDecimal meanDaily(List<WindowRun> runs, Money seed) {
        BigDecimal sum = BigDecimal.ZERO;
        long days = 0;
        for (WindowRun r : runs) {
            BigDecimal prev = seed.amount();
            for (EquityPoint p : r.equity()) {
                BigDecimal e = p.equity().amount();
                sum = sum.add(e.divide(prev, MC).subtract(BigDecimal.ONE), MC);
                prev = e;
                days++;
            }
        }
        return days == 0 ? null : sum.divide(BigDecimal.valueOf(days), MC);
    }

    private static Check c7(Evidence ev, Money seed, ScreeningThresholds t, java.time.LocalDate from,
            java.time.LocalDate to) {
        String title = "핵심 파라미터 ±20%";
        if (ev.low() == null || ev.high() == null) {
            return new Check("C7", title, Check.Outcome.NOT_AVAILABLE, ev.perturbNote());
        }
        List<List<WindowRun>> all = List.of(ev.train(), ev.low(), ev.high());
        for (List<WindowRun> l : all) {
            if (l.isEmpty() || !l.stream().allMatch(WindowRun::completed)) {
                return new Check("C7", title, Check.Outcome.NOT_AVAILABLE, "학습 구간 Run 없음 또는 실패");
            }
        }
        BigDecimal base = meanReturn(ev.train());
        BigDecimal low = meanReturn(ev.low());
        BigDecimal high = meanReturn(ev.high());
        String detail = ev.perturbKey() + " ×0.8 → " + ScreeningCriteria.pct(low) + ", ×1.2 → " + ScreeningCriteria.pct(high)
                + " (원래 " + ScreeningCriteria.pct(base) + ", 학습 구간 평균)";
        if (base.signum() <= 0) {
            return new Check("C7", title, Check.Outcome.FAIL, detail + ", 원래 학습 구간 손실");
        }
        BigDecimal floor = base.multiply(t.minPerturbedRatio());
        boolean ok = robust(low, ev.low(), seed, floor, t, from, to) && robust(high, ev.high(), seed, floor, t, from, to);
        return new Check("C7", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL, detail);
    }

    private static boolean robust(BigDecimal meanReturn, List<WindowRun> runs, Money seed, BigDecimal floor,
            ScreeningThresholds t, java.time.LocalDate from, java.time.LocalDate to) {
        if (meanReturn.compareTo(floor) < 0) {
            return false;
        }
        RunMetrics m = Pooled.of(seed, runs, from, to, null).metrics();
        BigDecimal pf = m.profitFactor();
        return pf == null ? m.roundTripCount() > 0 : pf.compareTo(t.minPerturbedProfitFactor()) >= 0;
    }

    private static BigDecimal meanReturn(List<WindowRun> runs) {
        BigDecimal sum = BigDecimal.ZERO;
        for (WindowRun r : runs) {
            sum = sum.add(r.metrics().totalReturn(), MC);
        }
        return sum.divide(BigDecimal.valueOf(runs.size()), MC);
    }

    /** C8: 이은 검증 곡선의 달(YYYY-MM)별 손익 중 가장 큰 달을 빼고도 수익인지. */
    private static Check c8(Pooled val, Money seed) {
        String title = "최고 1개월 제외 후에도 수익";
        Map<YearMonth, BigDecimal> monthly = new TreeMap<>();
        BigDecimal prev = seed.amount();
        for (EquityPoint p : val.chain()) {
            BigDecimal e = p.equity().amount();
            monthly.merge(YearMonth.from(p.date()), e.subtract(prev), BigDecimal::add);
            prev = e;
        }
        if (monthly.isEmpty()) {
            return new Check("C8", title, Check.Outcome.NOT_AVAILABLE, "자산 곡선 없음");
        }
        BigDecimal total = prev.subtract(seed.amount());
        Map.Entry<YearMonth, BigDecimal> best = null;
        for (Map.Entry<YearMonth, BigDecimal> e : monthly.entrySet()) {
            if (best == null || e.getValue().compareTo(best.getValue()) > 0) {
                best = e;
            }
        }
        BigDecimal rest = total.subtract(best.getValue());
        String detail = "전체 " + ScreeningCriteria.won(total) + ", 최고 달 " + best.getKey() + " "
                + ScreeningCriteria.won(best.getValue()) + ", 나머지 " + ScreeningCriteria.won(rest);
        return new Check("C8", title, total.signum() > 0 && rest.signum() > 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                detail);
    }

    private static Check c9d(Pooled stress, ScreeningThresholds t) {
        String title = "슬리피지 2배에서 수익·PF ≥ " + t.minStressProfitFactor();
        if (!stress.complete()) {
            return new Check("C9d", title, Check.Outcome.NOT_AVAILABLE, "스트레스 Run 없음 또는 실패");
        }
        RunMetrics m = stress.metrics();
        boolean pfOk = m.profitFactor() == null ? m.roundTripCount() > 0 && m.totalReturn().signum() > 0
                : m.profitFactor().compareTo(t.minStressProfitFactor()) >= 0;
        boolean ok = m.totalReturn().signum() > 0 && pfOk;
        return new Check("C9d", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "수익률 " + ScreeningCriteria.pct(m.totalReturn()) + ", PF " + ScreeningCriteria.num(m.profitFactor()));
    }

    /** C10 경험적 p = (k+1)/(N+1). k = 후보 값 이상인 대조군 수, N = 완료된 대조군 수. p ≤ 0.05/m이면 통과. */
    private static Check c10(BigDecimal value, List<BigDecimal> nulls, int m) {
        String title = "왕복당 순손익의 대조군 대비 경험적 p ≤ 0.05/m (m=" + m + ")";
        int n = nulls.size();
        if (!c10Possible(n, m)) { // 최소 가능 p = 1/(N+1) > 0.05/m
            return new Check("C10", title, Check.Outcome.NOT_AVAILABLE, "완료된 대조군 표본 N=" + n + " — 가장 작은 p 1/(N+1)이 0.05/m="
                    + FAMILY_ALPHA.divide(BigDecimal.valueOf(m), MC).stripTrailingZeros().toPlainString()
                    + "보다 커서 판정 불가(N+1 ≥ " + 20L * m + " 필요)");
        }
        if (value == null) {
            return new Check("C10", title, Check.Outcome.NOT_AVAILABLE, "왕복 0건");
        }
        int k = countAtLeast(nulls, value);
        BigDecimal p = BigDecimal.valueOf(k + 1L).divide(BigDecimal.valueOf(n + 1L), MC);
        boolean ok = (k + 1L) * 20L * m <= n + 1L;
        return new Check("C10", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL,
                ScreeningCriteria.won(value) + "원, 대조군 중 이상 " + k + "/" + n + ", p=" + p.setScale(5, RoundingMode.HALF_EVEN)
                        .toPlainString());
    }

    /** 가장 작은 p(1/(N+1))가 0.05/m 이하인가: N+1 ≥ 20m. */
    static boolean c10Possible(int n, int m) {
        return n + 1L >= 20L * m;
    }

    private static int countAtLeast(List<BigDecimal> nulls, BigDecimal value) {
        int k = 0;
        for (BigDecimal v : nulls) {
            if (v.compareTo(value) >= 0) {
                k++;
            }
        }
        return k;
    }

    /** 경험적 p. 판정 불가(표본 부족·왕복 0)면 null. */
    static BigDecimal pValue(BigDecimal value, List<BigDecimal> nulls, int m) {
        if (value == null || !c10Possible(nulls.size(), m)) {
            return null;
        }
        return BigDecimal.valueOf(countAtLeast(nulls, value) + 1L).divide(BigDecimal.valueOf(nulls.size() + 1L), MC);
    }
}
