package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.study.Check;
import com.autotrade.sim.study.ScreeningThresholds;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 장중 승격 기준 L1~L11 판정(D-114)을 손 계산과 비교한다. */
class LiveJudgeCriteriaTest {

    private static final BigDecimal SEED = new BigDecimal("10000000");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 26);
    private static final ScreeningThresholds T = ScreeningThresholds.defaults();

    @Test
    void l6_splits_at_four_weeks_and_needs_both_halves_positive() {
        // 앞 4주(10-26 ~ 11-22): 10,000,000 → 10,200,000 (+200,000), 뒤(11-23 ~): 10,200,000 → 10,300,000 (+100,000)
        RunReport ok = run(equity("2026-10-30", 10_100_000, "2026-11-20", 10_200_000, "2026-11-23", 10_150_000,
                "2026-12-18", 10_300_000));
        Check c = LiveJudgeCriteria.l6(ok, SEED, FROM);
        assertThat(c.outcome()).isEqualTo(Check.Outcome.PASS);
        assertThat(c.detail()).isEqualTo("앞 200000원, 뒤 100000원 (경계 2026-11-23)");

        RunReport rearLoss = run(equity("2026-11-20", 10_200_000, "2026-12-18", 10_199_999));
        assertThat(LiveJudgeCriteria.l6(rearLoss, SEED, FROM).outcome()).isEqualTo(Check.Outcome.FAIL);
        RunReport frontLoss = run(equity("2026-11-20", 9_999_999, "2026-12-18", 10_500_000));
        assertThat(LiveJudgeCriteria.l6(frontLoss, SEED, FROM).outcome()).isEqualTo(Check.Outcome.FAIL);
        // 4거래일 시험 운용은 뒤 구간이 없다
        RunReport shortRun = run(equity("2026-10-30", 10_100_000));
        assertThat(LiveJudgeCriteria.l6(shortRun, SEED, FROM).outcome()).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    @Test
    void l8_drops_the_best_monday_week() {
        // 주별: 10-26 주 +100,000, 11-02 주 +300,000, 11-09 주 −50,000 → 전체 350,000, 최고 300,000, 나머지 50,000
        RunReport ok = run(equity("2026-10-27", 10_100_000, "2026-11-03", 10_300_000, "2026-11-06", 10_400_000,
                "2026-11-10", 10_350_000));
        Check c = LiveJudgeCriteria.l8(ok, SEED);
        assertThat(c.outcome()).isEqualTo(Check.Outcome.PASS);
        assertThat(c.detail()).isEqualTo("전체 350000원, 최고 주 300000원, 나머지 50000원 (3주)");
        RunReport concentrated = run(equity("2026-10-27", 10_100_000, "2026-11-03", 10_400_000, "2026-11-10", 10_250_000));
        assertThat(LiveJudgeCriteria.l8(concentrated, SEED).outcome()).isEqualTo(Check.Outcome.FAIL);
    }

    @Test
    void l10_empirical_p_with_bonferroni_and_minimum_sample() {
        List<BigDecimal> nulls = new ArrayList<>(Collections.nCopies(119, BigDecimal.ZERO));
        // k = 0, p = 1/120 = 0.05/6 → 통과(경계)
        Check pass = LiveJudgeCriteria.l10(BigDecimal.ONE, nulls, 6);
        assertThat(pass.outcome()).isEqualTo(Check.Outcome.PASS);
        assertThat(pass.detail()).isEqualTo("1원/왕복, 대조군 중 이상 0/119, p=0.00833");
        // 같은 값도 "이상"으로 센다: k = 119 → 탈락
        assertThat(LiveJudgeCriteria.l10(BigDecimal.ZERO, nulls, 6).outcome()).isEqualTo(Check.Outcome.FAIL);
        // k = 1, p = 2/120 > 0.05/6 → 탈락
        List<BigDecimal> oneAbove = new ArrayList<>(nulls);
        oneAbove.set(0, BigDecimal.TEN);
        assertThat(LiveJudgeCriteria.l10(BigDecimal.ONE, oneAbove, 6).outcome()).isEqualTo(Check.Outcome.FAIL);
        // N + 1 < 20m → 판정 불가, 왕복 0건 → 판정 불가
        assertThat(LiveJudgeCriteria.l10(BigDecimal.ONE, nulls.subList(0, 118), 6).outcome())
                .isEqualTo(Check.Outcome.NOT_AVAILABLE);
        assertThat(LiveJudgeCriteria.l10(null, nulls, 6).outcome()).isEqualTo(Check.Outcome.NOT_AVAILABLE);
        // m = 2면 N = 39로 판정할 수 있다
        assertThat(LiveJudgeCriteria.l10(BigDecimal.ONE, nulls.subList(0, 39), 2).outcome()).isEqualTo(Check.Outcome.PASS);
    }

    @Test
    void l11_compares_total_return_and_fill_count_with_the_recording() {
        RunReport live = run(metrics("0.05", "1.5", 100, 40), List.of());
        assertThat(LiveJudgeCriteria.l11(live, recorded(metrics("0.03", "1.5", 90, 36))).outcome())
                .isEqualTo(Check.Outcome.PASS); // 차 2%p, 체결 10건 = 10% (경계 포함)
        assertThat(LiveJudgeCriteria.l11(live, recorded(metrics("0.0299", "1.5", 100, 40))).outcome())
                .isEqualTo(Check.Outcome.FAIL);
        assertThat(LiveJudgeCriteria.l11(live, recorded(metrics("0.05", "1.5", 111, 40))).outcome())
                .isEqualTo(Check.Outcome.FAIL);
        assertThat(LiveJudgeCriteria.l11(live, new LiveJudgeCriteria.Recorded(null, "같은 날 녹화 없음: 2026-10-26")))
                .satisfies(c -> {
                    assertThat(c.outcome()).isEqualTo(Check.Outcome.NOT_AVAILABLE);
                    assertThat(c.detail()).isEqualTo("같은 날 녹화 없음: 2026-10-26");
                });
    }

    @Test
    void l7_needs_both_variants_at_half_the_return_and_pf_one() {
        RunMetrics base = metrics("0.10", "1.5", 80, 32);
        assertThat(LiveJudgeCriteria.l7(base, pair(metrics("0.05", "1.0", 80, 32), metrics("0.12", "2.0", 80, 32)), T)
                .outcome()).isEqualTo(Check.Outcome.PASS);
        assertThat(LiveJudgeCriteria.l7(base, pair(metrics("0.0499", "1.2", 80, 32), metrics("0.12", "2.0", 80, 32)), T)
                .outcome()).isEqualTo(Check.Outcome.FAIL);
        assertThat(LiveJudgeCriteria.l7(base, pair(metrics("0.08", "0.99", 80, 32), metrics("0.12", "2.0", 80, 32)), T)
                .outcome()).isEqualTo(Check.Outcome.FAIL);
        assertThat(LiveJudgeCriteria.l7(metrics("-0.01", "0.9", 80, 32),
                pair(metrics("0.05", "1.5", 80, 32), metrics("0.05", "1.5", 80, 32)), T).outcome())
                .isEqualTo(Check.Outcome.FAIL);
        assertThat(LiveJudgeCriteria.l7(base, new LiveJudgeCriteria.Perturbed("k", "1", "2", null, null,
                "±20% 변형이 파라미터 범위 밖"), T).outcome()).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    @Test
    void l9_needs_profit_and_pf_one_at_two_seconds() {
        assertThat(LiveJudgeCriteria.l9(run(metrics("0.001", "1.0", 10, 4), List.of()), T).outcome())
                .isEqualTo(Check.Outcome.PASS);
        assertThat(LiveJudgeCriteria.l9(run(metrics("0", "1.2", 10, 4), List.of()), T).outcome())
                .isEqualTo(Check.Outcome.FAIL);
        assertThat(LiveJudgeCriteria.l9(null, T).outcome()).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    @Test
    void verdict_is_fail_then_hold_then_candidate() {
        Check pass = new Check("L1", "", Check.Outcome.PASS, "");
        Check na = new Check("L4", "", Check.Outcome.NOT_AVAILABLE, "");
        Check fail = new Check("L5", "", Check.Outcome.FAIL, "");
        assertThat(LiveJudgeCriteria.verdict(List.of(pass, na, fail))).isEqualTo(LiveJudgeCriteria.Verdict.FAIL);
        assertThat(LiveJudgeCriteria.verdict(List.of(pass, na))).isEqualTo(LiveJudgeCriteria.Verdict.HOLD);
        assertThat(LiveJudgeCriteria.verdict(List.of(pass))).isEqualTo(LiveJudgeCriteria.Verdict.CANDIDATE);
    }

    @Test
    void a_failed_live_run_is_judged_fail_without_other_checks() {
        RunReport failed = new RunReport(new RunResult("R-A03", new AccountId("R-A03"), PROFILE.profileHash(), 1L,
                RunStatus.FAILED, null, "금지 전이", null), PROFILE, List.of(), List.of(), List.of(), List.of());
        LiveJudgeCriteria.Judgement j = LiveJudgeCriteria.evaluate(new LiveJudgeCriteria.Evidence(failed, null, null, null,
                List.of(), 6, null, SEED, FROM, T));
        assertThat(j.verdict()).isEqualTo(LiveJudgeCriteria.Verdict.FAIL);
        assertThat(j.checks()).singleElement().satisfies(c -> assertThat(c.detail()).contains("금지 전이"));
    }

    @Test
    void trade_gap_matches_first_entry_per_symbol_and_day() {
        LocalDate d1 = LocalDate.of(2026, 10, 26);
        LocalDate d2 = LocalDate.of(2026, 10, 27);
        RunReport live = run(metrics("0.01", "1.5", 4, 2), List.of(entry("005930", d1, "100", 1), entry("005930", d1, "105", 2),
                entry("000660", d1, "200", 3), entry("005930", d2, "110", 1)));
        RunReport rec = run(metrics("0.01", "1.5", 3, 2), List.of(entry("005930", d1, "101", 1), entry("005930", d2, "110", 1),
                entry("035420", d2, "50", 2)));
        LiveJudgeCriteria.TradeGap g = LiveJudgeCriteria.tradeGap(live, rec);
        assertThat(g.liveEntries()).isEqualTo(3);
        assertThat(g.matched()).isEqualTo(2);
        assertThat(g.entryMatch().setScale(6, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.666667");
        // (|101 − 100| ÷ 100 + 0) ÷ 2 = 0.005
        assertThat(g.priceGap()).isEqualByComparingTo("0.005");
    }

    @Test
    void exposure_is_the_mean_invested_share_at_each_close() {
        // 첫날 현금 2,000,000/평가 10,000,000 → 80%, 둘째 날 현금 10,000,000/평가 10,000,000 → 0%
        List<EquityPoint> points = List.of(point("2026-10-26", 10_000_000, 2_000_000), point("2026-10-27", 10_000_000, 10_000_000));
        RunReport r = new RunReport(result(metrics("0", "1", 0, 0)), PROFILE, List.of(), points, List.of(), List.of());
        assertThat(LiveJudgeCriteria.closeExposure(r)).isEqualByComparingTo("0.4");
    }

    @Test
    void intraday_exposure_weights_cost_by_time_inside_the_session() {
        // 10:00 2,000,000원어치 사서 11:57 반을 팔고 13:54에 나머지를 판다(장 6.5시간 = 23,400초)
        // 넓이 = 2,000,000 × 7,020초 + 1,000,000 × 7,020초 = 21,060,000,000 → ÷ (10,000,000 × 23,400) = 0.09
        LocalDate d = LocalDate.of(2026, 10, 26);
        List<AppliedFill> fills = List.of(fillAt("005930", d, Side.BUY, "10000", 200, "10:00:00"),
                fillAt("005930", d, Side.SELL, "10100", 100, "11:57:00"), fillAt("005930", d, Side.SELL, "9900", 100, "13:54:00"));
        RunReport r = new RunReport(result(metrics("0", "1", 3, 1)), PROFILE, fills,
                List.of(point("2026-10-26", 10_000_000, 10_000_000)), List.of(), List.of());
        assertThat(LiveJudgeCriteria.intradayExposure(r, SEED, ZoneId.of("Asia/Seoul"))).isEqualByComparingTo("0.09");
        // 마감에는 현금뿐이라 마감 노출은 0
        assertThat(LiveJudgeCriteria.closeExposure(r)).isEqualByComparingTo("0");
    }

    @Test
    void the_baseline_is_not_compared_with_itself() {
        RunReport base = run(equity("2026-10-30", 10_100_000));
        LiveJudgeCriteria.Judgement j = LiveJudgeCriteria.evaluate(new LiveJudgeCriteria.Evidence(base, base, null, null,
                List.of(), 6, null, SEED, FROM, T));
        assertThat(j.checks().get(0)).satisfies(c -> {
            assertThat(c.code()).isEqualTo("L1");
            assertThat(c.outcome()).isEqualTo(Check.Outcome.NOT_AVAILABLE);
        });
    }

    @Test
    void l5_short_sample_is_not_available_not_fail() {
        // 표본 부족은 판정하지 않는다(PR #180 strategy-researcher M2): 29건 판정 불가, 30건 통과
        Check short29 = l5(29);
        assertThat(short29.outcome()).isEqualTo(Check.Outcome.NOT_AVAILABLE);
        assertThat(short29.detail()).isEqualTo("29건(표본 부족, 판정 불가)");
        assertThat(l5(30).outcome()).isEqualTo(Check.Outcome.PASS);
    }

    @Test
    void l5_short_sample_does_not_hide_another_fail() {
        // 표본 부족(판정 불가)과 다른 탈락이 겹치면 탈락이다(PR #180 strategy-researcher n2):
        // 왕복 29건 + PF 0.5(L2 탈락) → 탈락, 같은 29건에 PF 2면 탈락 없이 보류
        LiveJudgeCriteria.Judgement weak = judge(run(metrics("0.10", "0.5", 58, 29), List.of()));
        assertThat(outcome(weak, "L5")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
        assertThat(outcome(weak, "L2")).isEqualTo(Check.Outcome.FAIL);
        assertThat(weak.verdict()).isEqualTo(LiveJudgeCriteria.Verdict.FAIL);

        LiveJudgeCriteria.Judgement fine = judge(run(metrics("0.10", "2", 58, 29), List.of()));
        assertThat(outcome(fine, "L5")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
        assertThat(fine.checks()).noneMatch(c -> c.outcome() == Check.Outcome.FAIL);
        assertThat(fine.verdict()).isEqualTo(LiveJudgeCriteria.Verdict.HOLD);
    }

    private static LiveJudgeCriteria.Judgement judge(RunReport base) {
        return LiveJudgeCriteria.evaluate(new LiveJudgeCriteria.Evidence(base, null, null, null, List.of(), 6, null, SEED,
                FROM, T));
    }

    private static Check.Outcome outcome(LiveJudgeCriteria.Judgement j, String code) {
        return j.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().outcome();
    }

    private static Check l5(int trips) {
        RunReport base = run(metrics("0.10", "2", trips * 2, trips), List.of());
        return LiveJudgeCriteria.evaluate(new LiveJudgeCriteria.Evidence(base, null, null, null, List.of(), 6, null, SEED,
                FROM, T)).checks().stream().filter(c -> c.code().equals("L5")).findFirst().orElseThrow();
    }

    // ---- 도우미 ----

    private static final StrategyProfile PROFILE = new StrategyProfile("kr-test", 1, Market.KRX, TradingStyle.INTRADAY,
            new TreeMap<>(Map.of("logic", "orb")));

    private static LiveJudgeCriteria.Perturbed pair(RunMetrics low, RunMetrics high) {
        return new LiveJudgeCriteria.Perturbed("signal.x", "8", "12", run(low, List.of()), run(high, List.of()), "");
    }

    private static LiveJudgeCriteria.Recorded recorded(RunMetrics m) {
        return new LiveJudgeCriteria.Recorded(run(m, List.of()), "");
    }

    private static RunMetrics metrics(String ret, String pf, int fills, int trips) {
        BigDecimal r = new BigDecimal(ret);
        return new RunMetrics(new Money(SEED.add(SEED.multiply(r)), Money.KRW), r, null, BigDecimal.ZERO, null,
                new BigDecimal("0.5"), new BigDecimal(pf), BigDecimal.ZERO, fills, trips, BigDecimal.ZERO, Money.zero(Money.KRW),
                null, null, null);
    }

    private static RunResult result(RunMetrics m) {
        return new RunResult("R-A03", new AccountId("R-A03"), PROFILE.profileHash(), 1L, RunStatus.COMPLETED,
                m.finalEquity(), "", m);
    }

    private static RunReport run(RunMetrics m, List<AppliedFill> fills) {
        return new RunReport(result(m), PROFILE, fills, List.of(), List.of(), List.of());
    }

    private static RunReport run(List<EquityPoint> equity) {
        return new RunReport(result(metrics("0", "1", 0, 0)), PROFILE, List.of(), equity, List.of(), List.of());
    }

    private static List<EquityPoint> equity(Object... dateEquity) {
        List<EquityPoint> out = new ArrayList<>();
        for (int i = 0; i < dateEquity.length; i += 2) {
            out.add(point((String) dateEquity[i], ((Number) dateEquity[i + 1]).longValue(), 0));
        }
        return out;
    }

    private static EquityPoint point(String date, long equity, long cash) {
        LocalDate d = LocalDate.parse(date);
        return new EquityPoint(d, d.atTime(15, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant(), Money.of(equity, Money.KRW),
                Money.of(cash, Money.KRW));
    }

    private static AppliedFill fillAt(String code, LocalDate date, Side side, String price, long qty, String time) {
        Symbol s = Symbol.krx(code);
        BigDecimal p = new BigDecimal(price);
        java.time.Instant t = date.atTime(java.time.LocalTime.parse(time)).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        Fill f = new Fill(IdempotencyKey.of(date, s, side, 1), 1, s, side, side == Side.BUY ? OrderIntent.ENTRY : OrderIntent.EXIT,
                qty, p, CostModels.krDefault(x -> KrxBoard.KOSPI).fees(side, s, date, p, qty), Money.zero(Money.KRW), 1, 2, t, t,
                date);
        return new AppliedFill(f, "lot-1", null);
    }

    private static AppliedFill entry(String code, LocalDate date, String price, int seq) {
        Symbol s = Symbol.krx(code);
        BigDecimal p = new BigDecimal(price);
        java.time.Instant t = date.atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        Fill f = new Fill(IdempotencyKey.of(date, s, Side.BUY, seq), 1, s, Side.BUY, OrderIntent.ENTRY, 1, p,
                CostModels.krDefault(x -> KrxBoard.KOSPI).fees(Side.BUY, s, date, p, 1), Money.zero(Money.KRW), 1, 2, t, t, date);
        return new AppliedFill(f, "lot-" + code + "-" + seq, null);
    }
}
