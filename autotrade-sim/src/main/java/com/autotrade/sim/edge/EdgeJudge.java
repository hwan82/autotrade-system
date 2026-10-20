package com.autotrade.sim.edge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 선정 엣지 사전 검사 판정(D-125 2장). 결과를 보기 전에 고정한 규칙이다.
 *
 * <ol>
 *   <li><b>판정 불가</b>: 판정 구간 전체 후보 왕복이 {@value #MIN_TRADES}건 미만이거나, 왕복이 1건 이상인 대조군 시드 수
 *       N′이 N′ + 1 &lt; 20m이면.</li>
 *   <li><b>E1 대조군 초과</b>: 판정 구간 전체의 후보 평균 X(주 통계)에 대해 경험적 p = (1 + #{대조군 ≥ X}) ÷ (N′ + 1) ≤ 0.05 ÷ m.</li>
 *   <li><b>E2 구간 일관성</b>: 판정 구간 중 후보 평균이 그 구간 대조군 평균들의 중앙값보다 큰 구간이 과반(2 × 양수 구간 &gt; W).</li>
 *   <li><b>E3 방향 열위 없음</b>(주 통계가 {@code RANGE}일 때만): 비용 차감 수익 {@code net}의 아래쪽 p = (1 + #{대조군 ≤ X_net})
 *       ÷ (N′ + 1) &gt; 0.05(본페로니 보정 없음, {@link #E3_ALPHA}). 변동성은 크지만 방향이 무작위보다 유의하게 나쁜 선정은 롱 단타에 쓰지 않는다.</li>
 * </ol>
 * E1·E2·(E3) 모두면 통과, 하나라도 아니면 탈락.
 */
public final class EdgeJudge {

    /** 판정에 필요한 최소 후보 왕복 수(D-066·D-071의 검증 30건과 같은 값). */
    static final int MIN_TRADES = 30;
    /**
     * E3(방향 열위)의 유의수준. 본페로니는 "엣지 있음" 주장의 거짓 양성을 막는 것이라, 거르는 쪽인 E3에 α/m을 쓰면 나쁜 선정이
     * 통과할 여지만 커진다. 그래서 E3는 보정 없이 0.05를 쓴다(PR #198 strategy-researcher n1, D-125 2장 4번).
     */
    static final double E3_ALPHA = 0.05;

    private EdgeJudge() {
    }

    /** 판정. */
    public enum Verdict {
        PASS("통과"),
        FAIL("탈락"),
        UNDECIDED("판정 불가");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 평균 하나(왕복 수와 합). */
    record Mean(long n, double sum) {
        double value() {
            return n == 0 ? Double.NaN : sum / n;
        }
    }

    /**
     * 구간 하나의 요약.
     *
     * @param controlRange 대조군 시드별 평균 변동폭(왕복 0건 시드는 NaN)
     * @param soleNoData 결측 사유 하나로만 빠진 종목 수(사유별 합)
     */
    record Segment(String label, boolean judged, int tradingDays, int activeDays, long gatePassedSum, int gatePassedMin,
            int gatePassedMedian, int gatePassedMax, int cappedDays, long hardPassSum, long selectedSum,
            long untradableSelected, Mean range, Mean net, Mean openAtHigh, double[] controlRange, double[] controlNet,
            double[] controlOpenAtHigh, Map<String, Integer> soleNoData) {

        double controlMedian(EdgePlan.Test test) {
            return median(test == EdgePlan.Test.RANGE ? controlRange : controlNet);
        }

        Mean candidate(EdgePlan.Test test) {
            return test == EdgePlan.Test.RANGE ? range : net;
        }

        /** 후보 평균이 대조군 중앙값보다 큰가(후보 왕복이나 대조군이 없으면 아님). */
        boolean positive(EdgePlan.Test test) {
            double x = candidate(test).value();
            double m = controlMedian(test);
            return !Double.isNaN(x) && !Double.isNaN(m) && x > m;
        }
    }

    /**
     * @param all 판정 구간 전체(합친 것)
     * @param segments 판정 구간, 그다음 기술 통계 구간
     * @param pHigh 주 통계 E1 p
     * @param pOther 다른 통계의 위쪽 p(참고)
     * @param pNetLow {@code net} 아래쪽 p(E3, 주 통계가 RANGE일 때만 판정에 씀)
     * @param positiveWindows E2 양수 구간 수
     * @param effectiveSeeds 판정 구간에서 왕복이 1건 이상인 대조군 시드 수 N′
     */
    record Result(EdgePlan.Test test, Verdict verdict, List<String> reasons, Segment all, List<Segment> segments,
            double pHigh, double pOther, double pNetLow, int positiveWindows, int effectiveSeeds, double alpha,
            EdgeSurvivorship.Result survivorship) {
    }

    static Result judge(EdgePlan plan, List<SelectionEdgeStudy.Day> days) {
        return judge(plan, days, null);
    }

    /** @param survivorship 공시 선정의 생존편향 확인(공시 지표를 쓰지 않으면 null). 통과하지 못하면 판정 불가 */
    static Result judge(EdgePlan plan, List<SelectionEdgeStudy.Day> days, EdgeSurvivorship.Result survivorship) {
        int w = plan.windows().size();
        int segCount = w + plan.descriptive().size();
        int n = plan.nullSeeds();
        Acc[] acc = new Acc[segCount + 1]; // 마지막은 판정 구간 전체
        for (int s = 0; s <= segCount; s++) {
            acc[s] = new Acc(n);
        }
        for (SelectionEdgeStudy.Day d : days) {
            acc[d.segment()].add(plan, d);
            if (d.segment() < w) {
                acc[segCount].add(plan, d);
            }
        }
        List<Segment> segments = new ArrayList<>();
        for (int s = 0; s < segCount; s++) {
            String label = s < w ? "W" + (s + 1) + " " + plan.windows().get(s)
                    : "D" + (s - w + 1) + " " + plan.descriptive().get(s - w);
            segments.add(acc[s].segment(label, s < w));
        }
        Segment all = acc[segCount].segment("판정 구간 전체", true);
        EdgePlan.Test test = plan.test();
        EdgePlan.Test other = test == EdgePlan.Test.RANGE ? EdgePlan.Test.NET : EdgePlan.Test.RANGE;
        double[] primary = test == EdgePlan.Test.RANGE ? all.controlRange() : all.controlNet();
        double[] secondary = test == EdgePlan.Test.RANGE ? all.controlNet() : all.controlRange();
        int effective = (int) Arrays.stream(primary).filter(v -> !Double.isNaN(v)).count();
        double x = all.candidate(test).value();
        double pHigh = pUpper(primary, x);
        double pOther = pUpper(secondary, all.candidate(other).value());
        double pNetLow = pLower(all.controlNet(), all.net().value());
        int positive = 0;
        for (int s = 0; s < w; s++) {
            if (segments.get(s).positive(test)) {
                positive++;
            }
        }
        double alpha = plan.alpha();
        List<String> reasons = new ArrayList<>();
        Verdict verdict;
        if (all.candidate(test).n() < MIN_TRADES || effective + 1 < 20 * plan.familySize()) {
            verdict = Verdict.UNDECIDED;
            if (all.candidate(test).n() < MIN_TRADES) {
                reasons.add("후보 왕복 " + all.candidate(test).n() + "건 < " + MIN_TRADES);
            }
            if (effective + 1 < 20 * plan.familySize()) {
                reasons.add("왕복이 있는 대조군 시드 " + effective + "개, N′ + 1 < 20m");
            }
        } else {
            boolean e1 = pHigh <= alpha;
            boolean e2 = positive * 2 > w;
            boolean e3 = e3Passes(test, pNetLow);
            reasons.add("E1 " + (e1 ? "통과" : "미달") + ": p = " + EdgeReport.p(pHigh) + (e1 ? " ≤ " : " > ")
                    + EdgeReport.p(alpha));
            reasons.add("E2 " + (e2 ? "통과" : "미달") + ": 대조군 중앙값 초과 구간 " + positive + "/" + w);
            if (test == EdgePlan.Test.RANGE) {
                reasons.add("E3 " + (e3 ? "통과" : "미달") + ": net 아래쪽 p = " + EdgeReport.p(pNetLow)
                        + (e3 ? " > " : " ≤ ") + EdgeReport.p(E3_ALPHA) + "(보정 없음)");
            }
            verdict = e1 && e2 && e3 ? Verdict.PASS : Verdict.FAIL;
        }
        if (survivorship != null && !survivorship.passed()) {
            reasons.add("생존편향 미확인: 상장폐지 종목 연 단위 덮임률이 기준(유지 종목의 " + EdgeSurvivorship.MIN_RATIO
                    + "배, 폐지 종목 " + EdgeSurvivorship.MIN_DELISTED + "개 이상)에 못 미침. 위 E1~E3과 관계없이 판정 불가");
            verdict = Verdict.UNDECIDED;
        }
        return new Result(test, verdict, List.copyOf(reasons), all, List.copyOf(segments), pHigh, pOther, pNetLow,
                positive, effective, alpha, survivorship);
    }

    /** E3: 주 통계가 RANGE가 아니면 보지 않고, RANGE면 net 아래쪽 p &gt; {@link #E3_ALPHA}. */
    static boolean e3Passes(EdgePlan.Test test, double pNetLow) {
        return test != EdgePlan.Test.RANGE || pNetLow > E3_ALPHA;
    }

    /** (1 + #{v ≥ x}) ÷ (N′ + 1). NaN 시드는 뺀다. x가 NaN이면 1. */
    static double pUpper(double[] controls, double x) {
        if (Double.isNaN(x)) {
            return 1.0;
        }
        int k = 0;
        int m = 0;
        for (double v : controls) {
            if (!Double.isNaN(v)) {
                m++;
                if (v >= x) {
                    k++;
                }
            }
        }
        return (1.0 + k) / (m + 1.0);
    }

    /** (1 + #{v ≤ x}) ÷ (N′ + 1). */
    static double pLower(double[] controls, double x) {
        if (Double.isNaN(x)) {
            return 1.0;
        }
        int k = 0;
        int m = 0;
        for (double v : controls) {
            if (!Double.isNaN(v)) {
                m++;
                if (v <= x) {
                    k++;
                }
            }
        }
        return (1.0 + k) / (m + 1.0);
    }

    /** NaN을 뺀 중앙값(짝수면 가운데 두 값의 평균). 값이 없으면 NaN. */
    static double median(double[] values) {
        double[] v = Arrays.stream(values).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (v.length == 0) {
            return Double.NaN;
        }
        int h = v.length / 2;
        return v.length % 2 == 1 ? v[h] : (v[h - 1] + v[h]) / 2;
    }

    /** NaN을 뺀 경험 분위수: 정렬한 값의 ⌈q(N′ + 1)⌉번째(1부터, 범위로 자름). */
    static double quantile(double[] values, double q) {
        double[] v = Arrays.stream(values).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (v.length == 0) {
            return Double.NaN;
        }
        int k = (int) Math.ceil(q * (v.length + 1)) - 1;
        return v[Math.max(0, Math.min(v.length - 1, k))];
    }

    /** 구간 하나의 누적. */
    private static final class Acc {
        final int seeds;
        int tradingDays;
        int activeDays;
        long gatePassedSum;
        final List<Integer> gatePassed = new ArrayList<>();
        int cappedDays;
        long hardPassSum;
        long selectedSum;
        long untradable;
        long n;
        double range;
        double net;
        long openAtHigh;
        final long[] cn;
        final double[] cr;
        final double[] cnet;
        final long[] coah;
        final Map<String, Integer> soleNoData = new TreeMap<>();

        Acc(int seeds) {
            this.seeds = seeds;
            cn = new long[seeds];
            cr = new double[seeds];
            cnet = new double[seeds];
            coah = new long[seeds];
        }

        void add(EdgePlan plan, SelectionEdgeStudy.Day d) {
            tradingDays++;
            int k = d.selected().length;
            gatePassed.add(d.gatePassed());
            gatePassedSum += d.gatePassed();
            if (d.gatePassed() > k) {
                cappedDays++;
            }
            hardPassSum += d.hardPass();
            selectedSum += k;
            d.soleNoData().forEach((r, c) -> soleNoData.merge(r, c, Integer::sum));
            if (k == 0) {
                return;
            }
            activeDays++;
            for (int idx : d.selected()) {
                if (Double.isNaN(d.range()[idx])) {
                    untradable++;
                } else {
                    n++;
                    range += d.range()[idx];
                    net += d.net()[idx];
                    openAtHigh += d.openAtHigh()[idx] ? 1 : 0;
                }
            }
            for (int i = 0; i < seeds; i++) {
                for (int idx : SelectionEdgeStudy.sample(plan.seed(), i, d.t(), d.hardPass(), k)) {
                    if (!Double.isNaN(d.range()[idx])) {
                        cn[i]++;
                        cr[i] += d.range()[idx];
                        cnet[i] += d.net()[idx];
                        coah[i] += d.openAtHigh()[idx] ? 1 : 0;
                    }
                }
            }
        }

        Segment segment(String label, boolean judged) {
            int[] gp = gatePassed.stream().mapToInt(Integer::intValue).sorted().toArray();
            double[] r = new double[seeds];
            double[] nt = new double[seeds];
            double[] oah = new double[seeds];
            for (int i = 0; i < seeds; i++) {
                r[i] = cn[i] == 0 ? Double.NaN : cr[i] / cn[i];
                nt[i] = cn[i] == 0 ? Double.NaN : cnet[i] / cn[i];
                oah[i] = cn[i] == 0 ? Double.NaN : (double) coah[i] / cn[i];
            }
            return new Segment(label, judged, tradingDays, activeDays, gatePassedSum, gp.length == 0 ? 0 : gp[0],
                    gp.length == 0 ? 0 : gp[(gp.length - 1) / 2], gp.length == 0 ? 0 : gp[gp.length - 1], cappedDays,
                    hardPassSum, selectedSum, untradable, new Mean(n, range), new Mean(n, net),
                    new Mean(n, openAtHigh), r, nt, oah, java.util.Collections.unmodifiableMap(new TreeMap<>(soleNoData)));
        }
    }
}
