package com.autotrade.sim.live;

import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.study.Check;
import com.autotrade.sim.study.ScreeningCriteria;
import com.autotrade.sim.study.ScreeningThresholds;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 장중 프로필 8주 실시간 모의 승격 후보 판정(6단계 계획 4.2, 사용자 확정 D-114). 모든 값은 비용 차감 후다. 원래 성과는 입력 기록
 * 리플레이(= 실시간 결과, D-076 합격선)이고, 변형·지연·대조군은 같은 입력 기록으로 돌린 것이다(D-116). 수치는
 * {@link ScreeningThresholds#defaults()}(D-027 C1~C10과 같음)와 {@link #L11_MAX_RETURN_GAP}·{@link #L11_MAX_FILL_GAP}이다.
 *
 * <table>
 *   <caption>판정 항목</caption>
 *   <tr><th>코드</th><th>기준</th></tr>
 *   <tr><td>L1</td><td>총수익률 &gt; 같은 세션 기준선(매수 후 보유) 총수익률</td></tr>
 *   <tr><td>L2</td><td>PF ≥ 1.3</td></tr>
 *   <tr><td>L3</td><td>MDD ≤ 20%</td></tr>
 *   <tr><td>L4</td><td>샤프 ≥ 1.0(관측 20거래일 미만이면 판정 불가)</td></tr>
 *   <tr><td>L5</td><td>왕복 ≥ 30(미달이면 표본 부족으로 판정 불가)</td></tr>
 *   <tr><td>L6</td><td>앞 4주(운용 시작일부터 28일 미만)와 뒤 4주 각각 순손익 &gt; 0</td></tr>
 *   <tr><td>L7</td><td>study.key-param ±20% 변형이 원래 총수익률의 50% 이상이고 PF ≥ 1.0(원래가 손실이면 탈락)</td></tr>
 *   <tr><td>L8</td><td>가장 좋은 한 주(월요일 시작)를 빼도 순손익 &gt; 0</td></tr>
 *   <tr><td>L9</td><td>주문 지연 2초에서 총수익률 &gt; 0이고 PF ≥ 1.0</td></tr>
 *   <tr><td>L10</td><td>왕복당 순손익의 경험적 p = (k+1)/(N+1) ≤ 0.05/m. N + 1 &lt; 20m이면 판정 불가</td></tr>
 *   <tr><td>L11</td><td>실시간 대 같은 날 게이트웨이 녹화 리플레이: 총수익률 차 ≤ 2%p, 체결 수 차 ≤ 실시간 체결 수의 10%</td></tr>
 * </table>
 *
 * <p>판정: 탈락(FAIL)이 하나라도 있으면 탈락, 아니면 판정 불가가 있으면 보류, 모두 통과면 후보. L5 미달은 탈락이 아니라 표본
 * 부족으로 판정 불가다(PR #180 strategy-researcher M2): 다른 항목에 탈락이 없으면 보류, 있으면 탈락이다. 4단계 워크포워드(C5
 * 미달이면 다른 항목과 상관없이 보류)와는 다르다.
 * 대부분 L5 미달이면 기준을 낮추지 않고 등록을 늘리는 안을 사용자에게 묻는다(6단계 계획 4.2 되돌릴 조건).
 */
final class LiveJudgeCriteria {

    /** L11 총수익률 차 상한 `[가정]`(D-114 4번). */
    static final BigDecimal L11_MAX_RETURN_GAP = new BigDecimal("0.02");
    /** L11 체결 수 차 상한(실시간 체결 수 대비) `[가정]`(D-114 4번). */
    static final BigDecimal L11_MAX_FILL_GAP = new BigDecimal("0.10");
    /** L6 앞 구간 길이(달력 일). 8주 운용의 앞 4주. */
    static final int L6_FRONT_DAYS = 28;

    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);
    private static final BigDecimal FAMILY_ALPHA = new BigDecimal("0.05");

    private LiveJudgeCriteria() {
    }

    enum Verdict {
        CANDIDATE("후보"),
        FAIL("탈락"),
        HOLD("보류");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** 변형 한 쌍(L7). 변형을 만들 수 없으면 {@code low}·{@code high}가 null이고 이유가 {@code note}. */
    record Perturbed(String key, String lowValue, String highValue, RunReport low, RunReport high, String note) {
    }

    /** 실시간 대 녹화(L11). 녹화로 돌리지 못했으면 {@code recording}이 null이고 이유가 {@code note}. */
    record Recorded(RunReport recording, String note) {
    }

    /**
     * 한 프로필의 판정 근거.
     *
     * @param base 원래(입력 기록 리플레이 = 실시간, 기본 지연)
     * @param baseline 같은 세션 기준선 Run(L1)
     * @param stress 지연 2초 Run(L9). 대조군처럼 지연 레인에 없으면 null
     * @param nullPerTrip 대조군 시드별 왕복당 순손익(완료·왕복 1건 이상만)
     * @param recorded 녹화 리플레이(L11)
     */
    record Evidence(RunReport base, RunReport baseline, Perturbed perturbed, RunReport stress, List<BigDecimal> nullPerTrip,
            int m, Recorded recorded, BigDecimal seed, LocalDate from, ScreeningThresholds t) {
        Evidence {
            Objects.requireNonNull(base, "base");
            nullPerTrip = List.copyOf(nullPerTrip);
        }
    }

    record Judgement(Verdict verdict, List<Check> checks) {
        Judgement {
            checks = List.copyOf(checks);
        }
    }

    static Judgement evaluate(Evidence ev) {
        List<Check> checks = new ArrayList<>();
        RunMetrics m = ev.base().summary().metrics();
        if (m == null) {
            checks.add(new Check("L0", "Run 완료", Check.Outcome.FAIL, "실시간 Run 실패: " + ev.base().summary().failureReason()));
            return new Judgement(Verdict.FAIL, checks);
        }
        ScreeningThresholds t = ev.t();
        checks.add(l1(ev.base(), m, ev.baseline()));
        checks.add(ScreeningCriteria.profitFactor("L2", "PF ≥ " + t.minProfitFactor(), m, t.minProfitFactor()));
        checks.add(ScreeningCriteria.threshold("L3", "MDD ≤ " + ScreeningCriteria.pct(t.maxDrawdown()), m.maxDrawdown(),
                t.maxDrawdown(), false, ScreeningCriteria.pct(m.maxDrawdown())));
        checks.add(m.sharpe() == null
                ? new Check("L4", "샤프 ≥ " + t.minSharpe(), Check.Outcome.NOT_AVAILABLE,
                        "관측 " + ev.base().equityCurve().size() + "거래일(20일 미만이거나 변동 없음)")
                : ScreeningCriteria.threshold("L4", "샤프 ≥ " + t.minSharpe(), m.sharpe(), t.minSharpe(), true,
                        ScreeningCriteria.num(m.sharpe())));
        // 표본이 모자라면 판정하지 않는다(D-066 2번·D-072와 같음). 다른 탈락이 없으면 보류(PR #180 strategy-researcher M2)
        checks.add(m.roundTripCount() >= t.minRoundTrips()
                ? new Check("L5", "왕복 ≥ " + t.minRoundTrips(), Check.Outcome.PASS, m.roundTripCount() + "건")
                : new Check("L5", "왕복 ≥ " + t.minRoundTrips(), Check.Outcome.NOT_AVAILABLE,
                        m.roundTripCount() + "건(표본 부족, 판정 불가)"));
        checks.add(l6(ev.base(), ev.seed(), ev.from()));
        checks.add(l7(m, ev.perturbed(), t));
        checks.add(l8(ev.base(), ev.seed()));
        checks.add(l9(ev.stress(), t));
        checks.add(l10(perTrip(ev.base(), ev.seed()), ev.nullPerTrip(), ev.m()));
        checks.add(l11(ev.base(), ev.recorded()));
        return new Judgement(verdict(checks), checks);
    }

    /** 돌리지 않은 항목({@code codes})을 뺀 판정. 판정은 남은 항목으로 다시 낸다(참고 계좌, ADR-014 3.2). */
    static Judgement without(Judgement j, java.util.Collection<String> codes) {
        List<Check> kept = j.checks().stream().filter(c -> !codes.contains(c.code())).toList();
        return new Judgement(verdict(kept), kept);
    }

    static Verdict verdict(List<Check> checks) {
        boolean na = false;
        for (Check c : checks) {
            if (c.outcome() == Check.Outcome.FAIL) {
                return Verdict.FAIL;
            }
            na |= c.outcome() == Check.Outcome.NOT_AVAILABLE;
        }
        return na ? Verdict.HOLD : Verdict.CANDIDATE;
    }

    private static Check l1(RunReport self, RunMetrics m, RunReport baseline) {
        String title = "총수익률 > 기준선";
        if (baseline == self) {
            return new Check("L1", title, Check.Outcome.NOT_AVAILABLE, "기준선 자신(비교 대상)");
        }
        RunMetrics b = baseline == null ? null : baseline.summary().metrics();
        if (b == null) {
            return new Check("L1", title, Check.Outcome.NOT_AVAILABLE, "기준선 Run 없음");
        }
        BigDecimal excess = m.totalReturn().subtract(b.totalReturn());
        return new Check("L1", title, excess.signum() > 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                ScreeningCriteria.pct(m.totalReturn()) + " vs " + ScreeningCriteria.pct(b.totalReturn()) + " (초과 "
                        + ScreeningCriteria.pct(excess) + ")");
    }

    /** L6: 운용 시작일부터 {@value #L6_FRONT_DAYS}일 미만 거래일과 그 뒤 거래일의 순손익(일별 평가금액 차). */
    static Check l6(RunReport r, BigDecimal seed, LocalDate from) {
        String title = "앞 4주·뒤 4주 각각 순손익 > 0";
        LocalDate boundary = from.plusDays(L6_FRONT_DAYS);
        BigDecimal frontEnd = null;
        BigDecimal last = null;
        boolean back = false;
        for (EquityPoint p : r.equityCurve()) {
            BigDecimal e = p.equity().amount();
            if (p.date().isBefore(boundary)) {
                frontEnd = e;
            } else {
                back = true;
            }
            last = e;
        }
        if (frontEnd == null || !back) {
            return new Check("L6", title, Check.Outcome.NOT_AVAILABLE,
                    "앞 구간(~" + boundary.minusDays(1) + ")이나 뒤 구간(" + boundary + "~)에 거래일이 없음");
        }
        BigDecimal front = frontEnd.subtract(seed);
        BigDecimal rear = last.subtract(frontEnd);
        boolean ok = front.signum() > 0 && rear.signum() > 0;
        return new Check("L6", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "앞 " + ScreeningCriteria.won(front) + "원, 뒤 " + ScreeningCriteria.won(rear) + "원 (경계 " + boundary + ")");
    }

    static Check l7(RunMetrics base, Perturbed p, ScreeningThresholds t) {
        String title = "주요 파라미터 ±20%";
        if (p == null || p.low() == null || p.high() == null) {
            return new Check("L7", title, Check.Outcome.NOT_AVAILABLE, p == null ? "study.key-param 없음" : p.note());
        }
        String detail = p.key() + " " + p.lowValue() + "→" + ret(p.low()) + " PF " + pf(p.low()) + ", " + p.highValue() + "→"
                + ret(p.high()) + " PF " + pf(p.high()) + " (원래 " + ScreeningCriteria.pct(base.totalReturn()) + ")";
        if (base.totalReturn().signum() <= 0) {
            return new Check("L7", title, Check.Outcome.FAIL, detail + ", 원래 손실");
        }
        BigDecimal floor = base.totalReturn().multiply(t.minPerturbedRatio());
        boolean ok = robust(p.low(), floor, t.minPerturbedProfitFactor()) && robust(p.high(), floor, t.minPerturbedProfitFactor());
        return new Check("L7", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL, detail);
    }

    private static boolean robust(RunReport r, BigDecimal minReturn, BigDecimal minPf) {
        RunMetrics m = r.summary().metrics();
        if (m == null || m.totalReturn().compareTo(minReturn) < 0) {
            return false;
        }
        return m.profitFactor() == null ? m.roundTripCount() > 0
                : m.profitFactor().compareTo(minPf) >= 0;
    }

    /** L8: 주(월요일 시작)별 손익 중 가장 큰 주를 빼고도 순손익 &gt; 0(D-027 C8과 같은 계산). */
    static Check l8(RunReport r, BigDecimal seed) {
        String title = "최고 1주 빼도 순손익 > 0";
        Map<LocalDate, BigDecimal> weekly = new TreeMap<>();
        BigDecimal prev = seed;
        for (EquityPoint p : r.equityCurve()) {
            BigDecimal e = p.equity().amount();
            weekly.merge(p.date().with(DayOfWeek.MONDAY), e.subtract(prev), BigDecimal::add);
            prev = e;
        }
        if (weekly.isEmpty()) {
            return new Check("L8", title, Check.Outcome.NOT_AVAILABLE, "자산 곡선 없음");
        }
        BigDecimal total = prev.subtract(seed);
        BigDecimal best = weekly.values().stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal rest = total.subtract(best);
        return new Check("L8", title, total.signum() > 0 && rest.signum() > 0 ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "전체 " + ScreeningCriteria.won(total) + "원, 최고 주 " + ScreeningCriteria.won(best) + "원, 나머지 "
                        + ScreeningCriteria.won(rest) + "원 (" + weekly.size() + "주)");
    }

    static Check l9(RunReport stress, ScreeningThresholds t) {
        String title = "지연 " + ScreeningCriteria.seconds(t.stressLatency()) + "초에서 수익·PF ≥ " + t.minStressProfitFactor();
        RunMetrics m = stress == null ? null : stress.summary().metrics();
        if (m == null) {
            return new Check("L9", title, Check.Outcome.NOT_AVAILABLE,
                    stress == null ? "Run 없음" : "Run 실패: " + stress.summary().failureReason());
        }
        boolean pfOk = m.profitFactor() == null ? m.roundTripCount() > 0 && m.totalReturn().signum() > 0
                : m.profitFactor().compareTo(t.minStressProfitFactor()) >= 0;
        boolean ok = m.totalReturn().signum() > 0 && pfOk;
        return new Check("L9", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "수익률 " + ScreeningCriteria.pct(m.totalReturn()) + ", PF " + ScreeningCriteria.num(m.profitFactor()));
    }

    /** L10 경험적 p = (k+1)/(N+1)(D-071 5번, 4단계 C10과 같은 식). k = 후보 값 이상인 대조군 수. */
    static Check l10(BigDecimal value, List<BigDecimal> nulls, int m) {
        String title = "왕복당 순손익 대조군 p ≤ 0.05/" + m;
        int n = nulls.size();
        if (n + 1L < 20L * m) {
            return new Check("L10", title, Check.Outcome.NOT_AVAILABLE, "완료된 대조군 N=" + n + ", N+1 ≥ " + 20L * m
                    + " 필요(가장 작은 p 1/(N+1)이 0.05/m보다 큼)");
        }
        if (value == null) {
            return new Check("L10", title, Check.Outcome.NOT_AVAILABLE, "왕복 0건");
        }
        int k = 0;
        for (BigDecimal v : nulls) {
            if (v.compareTo(value) >= 0) {
                k++;
            }
        }
        BigDecimal p = BigDecimal.valueOf(k + 1L).divide(BigDecimal.valueOf(n + 1L), MC);
        boolean ok = (k + 1L) * 20L * m <= n + 1L;
        return new Check("L10", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL, ScreeningCriteria.won(value)
                + "원/왕복, 대조군 중 이상 " + k + "/" + n + ", p=" + p.setScale(5, RoundingMode.HALF_EVEN).toPlainString());
    }

    /** 0.05/m(L10 문턱). */
    static BigDecimal alpha(int m) {
        return FAMILY_ALPHA.divide(BigDecimal.valueOf(m), MC);
    }

    static Check l11(RunReport live, Recorded recorded) {
        String title = "실시간 대 녹화: 수익률 차 ≤ " + ScreeningCriteria.pct(L11_MAX_RETURN_GAP) + "p, 체결 수 차 ≤ "
                + ScreeningCriteria.pct(L11_MAX_FILL_GAP);
        RunMetrics a = live.summary().metrics();
        RunMetrics b = recorded == null || recorded.recording() == null ? null : recorded.recording().summary().metrics();
        if (b == null) {
            return new Check("L11", title, Check.Outcome.NOT_AVAILABLE, recorded == null ? "녹화 없음"
                    : recorded.recording() == null ? recorded.note()
                    : "녹화 Run 실패: " + recorded.recording().summary().failureReason());
        }
        BigDecimal gap = a.totalReturn().subtract(b.totalReturn()).abs();
        long fillGap = Math.abs((long) a.fillCount() - b.fillCount());
        boolean fillsOk = BigDecimal.valueOf(fillGap).compareTo(BigDecimal.valueOf(a.fillCount()).multiply(L11_MAX_FILL_GAP)) <= 0;
        boolean ok = gap.compareTo(L11_MAX_RETURN_GAP) <= 0 && fillsOk;
        return new Check("L11", title, ok ? Check.Outcome.PASS : Check.Outcome.FAIL,
                "수익률 " + ScreeningCriteria.pct(a.totalReturn()) + " vs " + ScreeningCriteria.pct(b.totalReturn()) + ", 체결 "
                        + a.fillCount() + " vs " + b.fillCount());
    }

    /**
     * 거래 단위 괴리(L11 보강, 참고 표시. D-066 4번 정의를 장중에 옮김): 실시간 진입(종목, 거래일) 중 녹화에도 있는 비율과, 그렇게
     * 맞은 (종목, 거래일)의 첫 진입 체결가 차이 평균(|녹화 − 실시간| ÷ 실시간). 판정에는 쓰지 않는다(D-114 L11은 총수익률·체결 수).
     *
     * @return 진입 일치율(실시간 진입이 없으면 null), 체결가 괴리 평균(맞은 것이 없으면 null), 실시간 진입 수, 맞은 수
     */
    record TradeGap(BigDecimal entryMatch, BigDecimal priceGap, int liveEntries, int matched) {
    }

    static TradeGap tradeGap(RunReport live, RunReport recording) {
        Map<String, BigDecimal> a = firstEntries(live);
        Map<String, BigDecimal> b = firstEntries(recording);
        int matched = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : a.entrySet()) {
            BigDecimal other = b.get(e.getKey());
            if (other != null) {
                matched++;
                sum = sum.add(other.subtract(e.getValue()).abs().divide(e.getValue(), MC));
            }
        }
        return new TradeGap(a.isEmpty() ? null : BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(a.size()), MC),
                matched == 0 ? null : sum.divide(BigDecimal.valueOf(matched), MC), a.size(), matched);
    }

    /** 진입 매수 체결의 (종목|거래일) → 그날 그 종목 첫 진입 체결가. */
    private static Map<String, BigDecimal> firstEntries(RunReport r) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (AppliedFill f : r.fills()) {
            if (f.fill().side() == Side.BUY && f.fill().intent() == OrderIntent.ENTRY) {
                out.putIfAbsent(f.fill().symbol().code() + "|" + f.fill().exchangeDate(), f.fill().price());
            }
        }
        return out;
    }

    /** 왕복당 순손익(원) = (최종 평가금액 − 시드) ÷ 왕복 수(4단계 C10과 같음). 왕복 0이거나 실패면 null. */
    static BigDecimal perTrip(RunReport r, BigDecimal seed) {
        RunMetrics m = r.summary().metrics();
        if (m == null || m.roundTripCount() == 0) {
            return null;
        }
        return m.finalEquity().amount().subtract(seed).divide(BigDecimal.valueOf(m.roundTripCount()), MathContext.DECIMAL64);
    }

    /** 장중 노출을 잴 정규장 시각 `[가정]`(09:00~15:30. 수능일처럼 시각이 다른 날도 이 시각으로 잰다). */
    static final java.time.LocalTime SESSION_OPEN = java.time.LocalTime.of(9, 0);
    static final java.time.LocalTime SESSION_CLOSE = java.time.LocalTime.of(15, 30);

    /**
     * 마감 노출 = 거래일 마감마다 (평가금액 − 현금) ÷ 평가금액의 평균. 당일 청산하는 장중 프로필은 0에 가깝다(리포트 참고).
     */
    static BigDecimal closeExposure(RunReport r) {
        if (r.equityCurve().isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (EquityPoint p : r.equityCurve()) {
            BigDecimal e = p.equity().amount();
            if (e.signum() > 0) {
                sum = sum.add(e.subtract(p.cash().amount()).divide(e, MC));
            }
        }
        return sum.divide(BigDecimal.valueOf(r.equityCurve().size()), MC);
    }

    /**
     * 장중 노출 = 거래일마다 정규장({@link #SESSION_OPEN}~{@link #SESSION_CLOSE}) 동안 든 포지션의 매입 금액 × 시간 ÷ (시드머니 × 장
     * 시간)의 평균(PR #175 strategy-researcher R1: 장중 프로필과 기준선의 노출 비교). 체결 시각으로 재고, 매도는 평균 단가로 줄인다.
     * 장 밖 체결은 가장 가까운 장 시각으로 본다. 자산 곡선의 거래일마다 한 값이다.
     */
    static BigDecimal intradayExposure(RunReport r, BigDecimal seed, java.time.ZoneId zone) {
        if (r.equityCurve().isEmpty() || seed.signum() <= 0) {
            return null;
        }
        Map<LocalDate, List<AppliedFill>> byDay = new TreeMap<>();
        for (AppliedFill f : r.fills()) {
            byDay.computeIfAbsent(f.fill().exchangeDate(), d -> new ArrayList<>()).add(f);
        }
        Map<String, BigDecimal[]> book = new TreeMap<>(); // 종목 → {수량, 매입 금액}
        BigDecimal sessionSeconds = BigDecimal.valueOf(java.time.Duration.between(SESSION_OPEN, SESSION_CLOSE).toSeconds());
        BigDecimal sum = BigDecimal.ZERO;
        for (EquityPoint p : r.equityCurve()) {
            java.time.Instant open = p.date().atTime(SESSION_OPEN).atZone(zone).toInstant();
            java.time.Instant close = p.date().atTime(SESSION_CLOSE).atZone(zone).toInstant();
            java.time.Instant t = open;
            BigDecimal area = BigDecimal.ZERO;
            for (AppliedFill a : byDay.getOrDefault(p.date(), List.of())) {
                Fill f = a.fill();
                java.time.Instant at = f.fillTime().isBefore(open) ? open : f.fillTime().isAfter(close) ? close : f.fillTime();
                area = area.add(held(book).multiply(BigDecimal.valueOf(java.time.Duration.between(t, at).toSeconds())));
                t = at;
                BigDecimal[] pos = book.computeIfAbsent(f.symbol().code(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                BigDecimal q = BigDecimal.valueOf(f.quantity());
                if (f.side() == Side.BUY) {
                    pos[0] = pos[0].add(q);
                    pos[1] = pos[1].add(q.multiply(f.price()));
                } else if (pos[0].signum() > 0) {
                    BigDecimal left = pos[0].subtract(q).max(BigDecimal.ZERO);
                    pos[1] = pos[1].multiply(left).divide(pos[0], MC);
                    pos[0] = left;
                }
            }
            area = area.add(held(book).multiply(BigDecimal.valueOf(java.time.Duration.between(t, close).toSeconds())));
            sum = sum.add(area.divide(seed.multiply(sessionSeconds), MC));
        }
        return sum.divide(BigDecimal.valueOf(r.equityCurve().size()), MC);
    }

    private static BigDecimal held(Map<String, BigDecimal[]> book) {
        BigDecimal v = BigDecimal.ZERO;
        for (BigDecimal[] pos : book.values()) {
            v = v.add(pos[1]);
        }
        return v;
    }

    private static String ret(RunReport r) {
        RunMetrics m = r.summary().metrics();
        return m == null ? "실패" : ScreeningCriteria.pct(m.totalReturn());
    }

    private static String pf(RunReport r) {
        RunMetrics m = r.summary().metrics();
        return m == null ? "-" : ScreeningCriteria.num(m.profitFactor());
    }
}
