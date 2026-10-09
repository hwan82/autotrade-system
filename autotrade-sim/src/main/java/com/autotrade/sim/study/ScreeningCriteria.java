package com.autotrade.sim.study;

import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.strategy.StrategyProfile;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 선발 기준 판정(STR-04·13, autotrade-strategy-lab 스킬 5장). 모든 판정은 비용 차감 후 값이고, 기본 지연 실험을 쓴다.
 *
 * <table>
 *   <caption>판정 항목</caption>
 *   <tr><th>코드</th><th>내용</th><th>구간</th></tr>
 *   <tr><td>C1</td><td>매수 후 보유 기준선 대비 초과수익 &gt; 0</td><td>검증</td></tr>
 *   <tr><td>C2</td><td>Profit Factor ≥ 하한</td><td>검증</td></tr>
 *   <tr><td>C3</td><td>MDD ≤ 상한</td><td>검증</td></tr>
 *   <tr><td>C4</td><td>샤프 ≥ 하한</td><td>검증</td></tr>
 *   <tr><td>C5</td><td>왕복 거래 수 ≥ 하한</td><td>검증</td></tr>
 *   <tr><td>C6</td><td>하루 평균 수익률이 학습 구간의 60% 이상(학습 구간이 수익이어야 함). 예열 일수는 빼고 나눈다</td><td>학습·검증</td></tr>
 *   <tr><td>C7</td><td>핵심 파라미터 ±20% 변형이 원래 수익률의 50% 이상, PF ≥ 1.0</td><td>학습</td></tr>
 *   <tr><td>C8</td><td>가장 좋은 한 주를 빼도 수익(특정 기간 쏠림 없음)</td><td>검증</td></tr>
 *   <tr><td>C9</td><td>주문 지연 2초에서도 수익이고 PF ≥ 1.0(STR-13)</td><td>검증</td></tr>
 *   <tr><td>C10</td><td>왕복당 순손익이 무작위 진입 대조군 분포의 95분위 초과(다중 비교 보정 대용). 거래 수가 다른
 *       프로필끼리 총수익률을 비교하면 비용 부담 차이가 섞이므로 왕복 한 건당 값으로 맞춘다</td><td>검증</td></tr>
 * </table>
 */
public final class ScreeningCriteria {

    private static final MathContext MC = MathContext.DECIMAL64;

    private ScreeningCriteria() {
    }

    public static List<Screening> evaluate(StudyResult r) {
        StudyConfig c = r.config();
        ScreeningThresholds t = c.thresholds();
        ExperimentOutcome val = r.outcome("VALIDATION", c.baseLatency());
        ExperimentOutcome train = r.outcome("TRAIN", c.baseLatency());
        ExperimentOutcome stress = r.outcome("VALIDATION", t.stressLatency());
        RunSummary baseline = val.run(c.baselineProfileId());
        List<BigDecimal> nulls = nullPerTrip(r);
        List<Screening> out = new ArrayList<>();
        for (StrategyProfile p : c.profiles()) {
            boolean reference = p.id().equals(c.baselineProfileId()) || p.id().equals(c.controlProfileId());
            RunSummary v = val.run(p.id());
            List<Check> checks = new ArrayList<>();
            if (!v.completed()) {
                checks.add(new Check("C0", "Run 완료", Check.Outcome.FAIL, "검증 구간 Run 실패: " + v.failureReason()));
                out.add(new Screening(p.id(), reference, checks));
                continue;
            }
            RunMetrics m = v.metrics();
            checks.add(excess(m, baseline));
            checks.add(profitFactor("C2", "Profit Factor ≥ " + t.minProfitFactor(), m, t.minProfitFactor()));
            checks.add(threshold("C3", "MDD ≤ " + pct(t.maxDrawdown()), m.maxDrawdown(), t.maxDrawdown(), false,
                    pct(m.maxDrawdown())));
            checks.add(threshold("C4", "샤프 ≥ " + t.minSharpe(), m.sharpe(), t.minSharpe(), true, num(m.sharpe())));
            checks.add(m.roundTripCount() >= t.minRoundTrips()
                    ? new Check("C5", "왕복 ≥ " + t.minRoundTrips(), Check.Outcome.PASS, m.roundTripCount() + "건")
                    : new Check("C5", "왕복 ≥ " + t.minRoundTrips(), Check.Outcome.FAIL, m.roundTripCount() + "건"));
            int warmup = warmupDays(p);
            checks.add(outOfSample(train.run(p.id()), train.tradingDays() - warmup, v, val.tradingDays() - warmup,
                    t.minOosRatio()));
            checks.add(perturbation(r, p.id(), train.run(p.id()), t));
            checks.add(concentration(v, c.seedCapital().amount()));
            checks.add(latency(stress.run(p.id()), t));
            checks.add(versusNull(perTrip(v, c.seedCapital().amount()), nulls, t.nullPercentile()));
            out.add(new Screening(p.id(), reference, checks));
        }
        return out;
    }

    /**
     * 신호 계산에 지난 거래일이 필요한 프로필의 예열 일수({@code study.warmup-days}, 사전 등록). 실행기는 구간마다 전략을 새로
     * 만들고 구간 밖 시세를 주지 않으므로, 구간 앞 이 일수만큼은 신호가 날 수 없다. C6의 하루 평균은 이 일수를 뺀 날로 나눈다.
     * 예열을 실행기 계약으로 넣는 일은 sim-engine 변경 제안이다(D-027).
     */
    static int warmupDays(StrategyProfile p) {
        String v = p.parameters().get("study.warmup-days");
        return v == null ? 0 : Integer.parseInt(v);
    }

    /** 대조군 분포: 시드별 실험의 대조군 검증 구간 수익률(완료된 것만, 오름차순). */
    static List<BigDecimal> nullReturns(StudyResult r) {
        List<BigDecimal> out = new ArrayList<>();
        for (ExperimentOutcome o : r.nullRuns()) {
            RunSummary s = o.run(r.config().controlProfileId());
            if (s != null && s.completed()) {
                out.add(s.metrics().totalReturn());
            }
        }
        out.sort(BigDecimal::compareTo);
        return out;
    }

    /** 대조군 분포: 시드별 실험의 대조군 검증 구간 왕복당 순손익(원, 오름차순). */
    static List<BigDecimal> nullPerTrip(StudyResult r) {
        List<BigDecimal> out = new ArrayList<>();
        for (ExperimentOutcome o : r.nullRuns()) {
            BigDecimal v = perTrip(o.run(r.config().controlProfileId()), r.config().seedCapital().amount());
            if (v != null) {
                out.add(v);
            }
        }
        out.sort(BigDecimal::compareTo);
        return out;
    }

    /**
     * 왕복당 순손익(원) = (최종 평가금액 − 시드) / 왕복 수. 비용 차감 후이고, 끝까지 들고 있는 포지션의 평가손익도 들어간다.
     * 왕복이 없거나 Run이 실패면 null.
     */
    static BigDecimal perTrip(RunSummary s, BigDecimal seed) {
        if (s == null || !s.completed() || s.metrics().roundTripCount() == 0) {
            return null;
        }
        return s.metrics().finalEquity().amount().subtract(seed)
                .divide(BigDecimal.valueOf(s.metrics().roundTripCount()), MC);
    }

    /** 경험적 분위수(가장 가까운 순위, 1-기반 ceil(q × n)). */
    static BigDecimal quantile(List<BigDecimal> sorted, BigDecimal q) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = q.multiply(BigDecimal.valueOf(sorted.size())).setScale(0, RoundingMode.CEILING).intValue();
        return sorted.get(Math.max(1, Math.min(sorted.size(), rank)) - 1);
    }

    private static Check excess(RunMetrics m, RunSummary baseline) {
        String title = "기준선 대비 초과수익 > 0";
        if (baseline == null || !baseline.completed()) {
            return new Check("C1", title, Check.Outcome.NOT_AVAILABLE, "기준선 Run 없음");
        }
        BigDecimal excess = m.totalReturn().subtract(baseline.metrics().totalReturn());
        return new Check("C1", title, excess.signum() > 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "초과 " + pct(excess));
    }

    private static Check profitFactor(String code, String title, RunMetrics m, BigDecimal min) {
        if (m.roundTripCount() == 0) {
            return new Check(code, title, Check.Outcome.NOT_AVAILABLE, "왕복 0건");
        }
        if (m.profitFactor() == null) {
            // 손실 왕복이 없음: 수익 왕복만 있으면 무한대로 본다
            boolean anyWin = m.winRate() != null && m.winRate().signum() > 0;
            return new Check(code, title, anyWin ? Check.Outcome.PASS : Check.Outcome.FAIL, anyWin ? "손실 없음" : "손익 0");
        }
        return new Check(code, title, m.profitFactor().compareTo(min) >= 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                num(m.profitFactor()));
    }

    private static Check threshold(
            String code, String title, BigDecimal value, BigDecimal limit, boolean atLeast, String shown) {
        if (value == null) {
            return new Check(code, title, Check.Outcome.NOT_AVAILABLE, "계산 불가");
        }
        boolean ok = atLeast ? value.compareTo(limit) >= 0 : value.compareTo(limit) <= 0;
        return new Check(code, title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL, shown);
    }

    private static Check outOfSample(RunSummary train, int trainDays, RunSummary val, int valDays, BigDecimal minRatio) {
        String title = "검증/학습 하루 수익률 ≥ " + minRatio;
        if (trainDays < 1 || valDays < 1) {
            return new Check("C6", title, Check.Outcome.NOT_AVAILABLE, "예열을 빼면 남는 거래일이 없음");
        }
        if (train == null || !train.completed()) {
            return new Check("C6", title, Check.Outcome.NOT_AVAILABLE, "학습 구간 Run 없음");
        }
        BigDecimal trainDaily = train.metrics().totalReturn().divide(BigDecimal.valueOf(trainDays), MC);
        BigDecimal valDaily = val.metrics().totalReturn().divide(BigDecimal.valueOf(valDays), MC);
        String detail = "학습 " + bp(trainDaily) + "/일, 검증 " + bp(valDaily) + "/일";
        if (trainDaily.signum() <= 0) {
            return new Check("C6", title, Check.Outcome.FAIL, detail + " (학습 구간 손실)");
        }
        boolean ok = valDaily.compareTo(trainDaily.multiply(minRatio)) >= 0;
        return new Check("C6", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL, detail);
    }

    private static Check perturbation(StudyResult r, String profileId, RunSummary base, ScreeningThresholds t) {
        String title = "핵심 파라미터 ±20%";
        StrategyStudy.PerturbedPair pair = r.perturbations().get(profileId);
        if (pair == null || r.perturbation() == null) {
            return new Check("C7", title, Check.Outcome.NOT_AVAILABLE, "study.key-param 없음");
        }
        if (base == null || !base.completed()) {
            return new Check("C7", title, Check.Outcome.NOT_AVAILABLE, "학습 구간 Run 없음");
        }
        BigDecimal baseReturn = base.metrics().totalReturn();
        RunSummary low = r.perturbation().run(pair.lowId());
        RunSummary high = r.perturbation().run(pair.highId());
        String detail = pair.key() + " " + pair.lowValue() + "→" + ret(low) + ", " + pair.highValue() + "→" + ret(high)
                + " (원래 " + pct(baseReturn) + ")";
        if (baseReturn.signum() <= 0) {
            return new Check("C7", title, Check.Outcome.FAIL, detail + ", 원래 학습 구간 손실");
        }
        BigDecimal floor = baseReturn.multiply(t.minPerturbedRatio());
        boolean ok = robust(low, floor, t.minPerturbedProfitFactor()) && robust(high, floor, t.minPerturbedProfitFactor());
        return new Check("C7", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL, detail);
    }

    private static boolean robust(RunSummary s, BigDecimal minReturn, BigDecimal minPf) {
        if (s == null || !s.completed() || s.metrics().totalReturn().compareTo(minReturn) < 0) {
            return false;
        }
        BigDecimal pf = s.metrics().profitFactor();
        return pf == null ? s.metrics().roundTripCount() > 0 : pf.compareTo(minPf) >= 0;
    }

    /** C8: 주(월요일 시작)별 손익 중 가장 큰 주를 빼고도 수익인지. */
    private static Check concentration(RunSummary v, BigDecimal seed) {
        String title = "최고 1주 제외 후에도 수익";
        Map<LocalDate, BigDecimal> weekly = new TreeMap<>();
        BigDecimal prev = seed;
        for (EquityPoint p : v.equity()) {
            BigDecimal e = p.equity().amount();
            weekly.merge(p.date().with(DayOfWeek.MONDAY), e.subtract(prev), BigDecimal::add);
            prev = e;
        }
        BigDecimal total = prev.subtract(seed);
        if (weekly.isEmpty()) {
            return new Check("C8", title, Check.Outcome.NOT_AVAILABLE, "자산 곡선 없음");
        }
        BigDecimal best = weekly.values().stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal rest = total.subtract(best);
        String detail = "전체 " + won(total) + ", 최고 주 " + won(best) + ", 나머지 " + won(rest);
        return new Check("C8", title, total.signum() > 0 && rest.signum() > 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                detail);
    }

    private static Check latency(RunSummary s, ScreeningThresholds t) {
        String title = "지연 " + seconds(t.stressLatency()) + "초에서 수익·PF ≥ " + t.minStressProfitFactor();
        if (s == null || !s.completed()) {
            return new Check("C9", title, Check.Outcome.NOT_AVAILABLE, "Run 없음");
        }
        RunMetrics m = s.metrics();
        boolean pfOk = m.profitFactor() == null ? m.roundTripCount() > 0 && m.totalReturn().signum() > 0
                : m.profitFactor().compareTo(t.minStressProfitFactor()) >= 0;
        boolean ok = m.totalReturn().signum() > 0 && pfOk;
        return new Check("C9", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "수익률 " + pct(m.totalReturn()) + ", PF " + num(m.profitFactor()));
    }

    private static Check versusNull(BigDecimal value, List<BigDecimal> nulls, BigDecimal q) {
        String title = "왕복당 순손익이 대조군 " + q.movePointRight(2).stripTrailingZeros().toPlainString() + "분위 초과";
        BigDecimal threshold = quantile(nulls, q);
        if (threshold == null) {
            return new Check("C10", title, Check.Outcome.NOT_AVAILABLE, "대조군 Run 없음");
        }
        if (value == null) {
            return new Check("C10", title, Check.Outcome.NOT_AVAILABLE, "왕복 0건");
        }
        return new Check("C10", title, value.compareTo(threshold) > 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                won(value) + "원 vs " + won(threshold) + "원 (n=" + nulls.size() + ")");
    }

    private static String ret(RunSummary s) {
        return s == null || !s.completed() ? "실패" : pct(s.metrics().totalReturn());
    }

    static String pct(BigDecimal v) {
        return v == null ? "-" : v.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    static String bp(BigDecimal v) {
        return v == null ? "-" : v.movePointRight(4).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "bp";
    }

    static String num(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    static String seconds(java.time.Duration d) {
        return BigDecimal.valueOf(d.toMillis()).movePointLeft(3).stripTrailingZeros().toPlainString();
    }

    static String won(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_EVEN).toPlainString();
    }
}
