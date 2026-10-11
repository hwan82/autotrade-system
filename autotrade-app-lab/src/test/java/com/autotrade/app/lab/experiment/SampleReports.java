package com.autotrade.app.lab.experiment;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.cost.FeeBreakdown;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSeeds;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.sim.selection.SelectionDay;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 화면·API 테스트용 실험 결과. 실제 실행기 출력이 아니라 손으로 만든 값이며 실험 비교에 쓰지 않는다.
 * 계좌 A01은 이익, A02는 손실, A03은 실패한 Run이다.
 */
public final class SampleReports {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private SampleReports() {
    }

    public record Sample(ExperimentSpec spec, SimulationReport report) {
    }

    /** 조합 실험 샘플: 실험 명세·결과와 실험이 쓴 선정 결과 본문(일부만, 나머지는 본문 없음). */
    public record ComboSample(ExperimentSpec spec, SimulationReport report, List<SelectionResult> results) {
    }

    /** 샘플 선정 프로필: 예시 (가) 재무 4조건과 같은 키(selections/kr-fin-quality-growth/v1.yaml). */
    public static SelectionProfile finSelection() {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "재무 개선이 가격에 늦게 반영된다(샘플)");
        p.put("refresh", "POST_CLOSE_WEEKLY");
        p.put("max-symbols", "50");
        p.put("filter.kinds", "STOCK");
        p.put("gate.01.metric", "op-margin-growth");
        p.put("gate.01.years", "3");
        p.put("gate.01.op", "GE");
        p.put("gate.01.value", "0.5");
        p.put("gate.02.metric", "debt-ratio");
        p.put("gate.02.op", "LE");
        p.put("gate.02.value", "1.5");
        return new SelectionProfile("kr-fin-quality-growth", 1, Market.KRX, TradingStyle.POSITION, p);
    }

    /**
     * 선정 1개 × 매매지표 2개 + 선정 없음 대조군 = 4계좌(조합 실험 절차 1장 4번 모양). 20거래일이면 총수익률은 A01 0.033,
     * A02 0.011, A03(선정 없음 × 추세) 0.022, A04(선정 없음 × 무작위) −0.0055다(하루 증가분 × 11 ÷ 시드).
     * 선정 계좌의 첫 거래일은 결과 없음(MISSING, 막은 진입 2건)이고, 나머지 날은 ISO 주마다 결과 하나다.
     * 첫 두 주의 결과만 본문을 넣고(첫 주 결과에 정정 대체 규칙 1건), 그 뒤 주는 본문 없음이다.
     */
    public static ComboSample combos(String experimentId, int tradingDays) {
        List<LocalDate> days = weekdays(LocalDate.of(2026, 9, 1), tradingDays);
        LocalDate from = days.get(0);
        LocalDate to = days.get(days.size() - 1);
        SelectionProfile sel = finSelection();
        StrategyProfile trend = new StrategyProfile("kr-ma-trend-position", 1, Market.KRX, TradingStyle.POSITION,
                new TreeMap<>(java.util.Map.of("entry.signal", "ma-cross", "exit.max-hold-days", "120")));
        StrategyProfile random = new StrategyProfile("kr-random-daily-position", 1, Market.KRX, TradingStyle.POSITION,
                new TreeMap<>(java.util.Map.of("entry.signal", "random-daily", "exit.max-hold-days", "120")));
        List<Combination> combos = List.of(Combination.of(sel, trend), Combination.of(sel, random),
                Combination.of(trend), Combination.of(random));
        ExperimentSpec spec = new ExperimentSpec(new ExperimentId(experimentId), from, to, 42L,
                Money.of(10_000_000, Money.KRW), "KR-DEFAULT-2026", "DAILY-20260930", ClockMode.REPLAY, combos);

        List<SelectionResult> results = new ArrayList<>();
        java.util.Map<Integer, String> hashByWeek = new java.util.HashMap<>();
        java.util.Map<Integer, java.util.SortedSet<Symbol>> symbolsByWeek = new java.util.HashMap<>();
        int firstWeek = week(from);
        for (LocalDate d : days.subList(1, days.size())) {
            int w = week(d) - firstWeek;
            if (hashByWeek.containsKey(w)) {
                continue;
            }
            if (w < 2) {
                LocalDate weekEnd = d.with(java.time.DayOfWeek.FRIDAY);
                SelectionResult r = result(sel, d, weekEnd, w == 0);
                results.add(r);
                hashByWeek.put(w, r.contentHash());
                java.util.TreeSet<Symbol> syms = new java.util.TreeSet<>();
                r.selected().forEach(x -> syms.add(Symbol.krx(x.code())));
                symbolsByWeek.put(w, syms);
            } else {
                hashByWeek.put(w, String.format("%064x", w));
                symbolsByWeek.put(w, new java.util.TreeSet<>(List.of(Symbol.krx("005930"))));
            }
        }
        List<RunSelectionDay> selectionDays = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            LocalDate d = days.get(i);
            if (i == 0) {
                selectionDays.add(new RunSelectionDay(new SelectionDay(d, null, SelectionInvalidReason.MISSING,
                        new java.util.TreeSet<>()), 2));
            } else {
                int w = week(d) - firstWeek;
                selectionDays.add(new RunSelectionDay(new SelectionDay(d, hashByWeek.get(w), null,
                        symbolsByWeek.get(w)), i % 4 == 0 ? 1 : 0));
            }
        }
        String id = experimentId;
        List<RunReport> runs = List.of(
                withCombination(completed(id + "-A01", trend, days, 10_000_000, 30_000, 4, false), combos.get(0), selectionDays),
                withCombination(completed(id + "-A02", random, days, 10_000_000, 10_000, 4, false), combos.get(1), selectionDays),
                withCombination(completed(id + "-A03", trend, days, 10_000_000, 20_000, 4, false), combos.get(2), List.of()),
                withCombination(completed(id + "-A04", random, days, 10_000_000, -5_000, 4, false), combos.get(3), List.of()));
        ExperimentResult result = new ExperimentResult(spec.id(), "0123456789abcdef0123456789abcdef01234567", false,
                "c0ffee" + "0".repeat(58), "DAILY-NEXT-OPEN-V1", runs.stream().map(RunReport::summary).toList());
        return new ComboSample(spec, new SimulationReport(result, runs), results);
    }

    private static SelectionResult result(SelectionProfile sel, LocalDate applyFrom, LocalDate applyTo,
            boolean withFallback) {
        SelectionResult.Inputs inputs = new SelectionResult.Inputs("KRX-DAILY-20260930", "0".repeat(64),
                applyFrom.minusDays(1), "KRX-SHARES-20260930", applyFrom.minusDays(1), "DART-FIN-20260930",
                "1".repeat(64), "2026-08-31T09:00:00Z", "2".repeat(64));
        List<SelectionResult.Evaluation> evals = List.of(
                new SelectionResult.Evaluation("005930", true, List.of(), values("0.8", "0.4"), false),
                new SelectionResult.Evaluation("000660", true, List.of(), values("0.6", "0.9"), withFallback),
                new SelectionResult.Evaluation("035420", false, List.of("GATE_01"), values("0.1", "0.3"), false),
                new SelectionResult.Evaluation("051910", false, List.of("GATE_01", "GATE_02"), values("0.2", "2.1"),
                        false),
                new SelectionResult.Evaluation("068270", false, List.of("GATE_01_NO_DATA"), new TreeMap<>(), false));
        return new SelectionResult(SelectionResult.Selection.of(sel), applyFrom, applyTo, inputs,
                List.of(new SelectionResult.Selected("005930", 1), new SelectionResult.Selected("000660", 2)), evals);
    }

    private static TreeMap<String, String> values(String g1, String g2) {
        TreeMap<String, String> v = new TreeMap<>();
        v.put("gate.01", g1);
        v.put("gate.02", g2);
        return v;
    }

    private static int week(LocalDate d) {
        return d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private static List<LocalDate> weekdays(LocalDate from, int n) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; days.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                days.add(d);
            }
        }
        return days;
    }

    /** 실행기와 같게 요약의 선정·조합 해시와 시드 방식도 조합에 맞춘다(결과 묶음 읽기가 대조한다, D-062). */
    private static RunReport withCombination(RunReport r, Combination c, List<RunSelectionDay> selectionDays) {
        RunResult s = r.summary();
        RunResult summary = new RunResult(s.runId(), s.accountId(), s.profileHash(), c.selectionHashOrNone(),
                c.combinationHash(), s.runSeed(), c.hasSelection() ? RunSeeds.V2 : RunSeeds.V1, s.status(),
                s.finalEquity(), s.failureReason(), s.metrics());
        return new RunReport(summary, c, r.fills(), r.equityCurve(), r.roundTrips(), r.rejections(), selectionDays);
    }

    public static Sample krx(String experimentId, int tradingDays) {
        LocalDate from = LocalDate.of(2026, 9, 1);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; days.size() < tradingDays; d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                days.add(d);
            }
        }
        LocalDate to = days.get(days.size() - 1);
        StrategyProfile breakout = profile("breakout", 2, "entry.signal", "range-breakout", "exit.stop-loss-pct", "3");
        StrategyProfile meanRev = profile("mean-revert", 1, "entry.signal", "rsi-oversold", "exit.stop-loss-pct", "5");
        StrategyProfile broken = profile("broken", 1, "entry.signal", "unknown", "exit.stop-loss-pct", "1");
        Money seed = Money.of(10_000_000, Money.KRW);
        ExperimentSpec spec = ExperimentSpec.ofProfiles(new ExperimentId(experimentId), from, to, 42L, seed,
                "KR-DEFAULT-2026", "DAILY-20260930", ClockMode.REPLAY, List.of(breakout, meanRev, broken));

        RunReport a01 = completed(experimentId + "-A01", breakout, days, 10_000_000, 25_000, 12, true);
        RunReport a02 = completed(experimentId + "-A02", meanRev, days, 10_000_000, -18_000, 3, false);
        RunResult failedSummary = new RunResult(experimentId + "-A03", new AccountId(experimentId + "-A03"),
                broken.profileHash(), 7L, RunStatus.FAILED, null, "모르는 프로필: broken", null);
        RunReport a03 = new RunReport(failedSummary, broken, List.of(), List.of(), List.of(), List.of());
        ExperimentResult result = new ExperimentResult(spec.id(), "0123456789abcdef0123456789abcdef01234567", false,
                "c0ffee" + "0".repeat(58), "BOOK-V1+500ms", List.of(a01.summary(), a02.summary(), a03.summary()));
        return new Sample(spec, new SimulationReport(result, List.of(a01, a02, a03)));
    }

    private static StrategyProfile profile(String id, int version, String... kv) {
        TreeMap<String, String> params = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new StrategyProfile(id, version, Market.KRX, TradingStyle.SWING, params);
    }

    private static RunReport completed(
            String runId, StrategyProfile profile, List<LocalDate> days, long seed, long dailyStep, int fills,
            boolean blacklisted) {
        List<EquityPoint> curve = new ArrayList<>();
        long equity = seed;
        for (int i = 0; i < days.size(); i++) {
            equity += (i % 3 == 2 ? -dailyStep / 2 : dailyStep);
            Instant close = days.get(i).atTime(15, 30).atZone(SEOUL).toInstant();
            curve.add(new EquityPoint(days.get(i), close, Money.of(equity, Money.KRW), Money.of(equity / 2, Money.KRW)));
        }
        Symbol samsung = Symbol.krx("005930");
        List<AppliedFill> applied = new ArrayList<>();
        List<RoundTrip> trips = new ArrayList<>();
        for (int i = 0; i < fills; i++) {
            LocalDate d = days.get(i % days.size());
            Side side = i % 2 == 0 ? Side.BUY : Side.SELL;
            Instant signal = d.atTime(9, 10).atZone(SEOUL).toInstant();
            Fill fill = new Fill(IdempotencyKey.of(d, samsung, side, i / 2 + 1), 1, samsung, side,
                    side == Side.BUY ? OrderIntent.ENTRY : OrderIntent.EXIT, 10, new BigDecimal("71200"),
                    new FeeBreakdown(Money.of("106.8", Money.KRW), Money.of(side == Side.SELL ? 1424 : 0, Money.KRW),
                            new TreeMap<>()),
                    Money.of(1068, Money.KRW), 100L + i * 10, 101L + i * 10, signal, signal.plusMillis(500), d);
            String lot = runId + "-L" + (i / 2 + 1);
            Money pnl = side == Side.SELL ? Money.of(dailyStep / 10, Money.KRW) : null;
            applied.add(new AppliedFill(fill, lot, pnl));
            if (side == Side.SELL) {
                trips.add(new RoundTrip(lot, samsung, days.get((i - 1) % days.size()), d, pnl));
            }
        }
        List<OrderRejection> rejections = new ArrayList<>();
        LocalDate d0 = days.get(0);
        rejections.add(new OrderRejection(55, IdempotencyKey.of(d0, samsung, Side.BUY, 90), samsung, "EXCHANGE",
                "INSUFFICIENT_CASH", ""));
        if (blacklisted) {
            Symbol excluded = Symbol.krx("000660");
            for (int i = 0; i < 3; i++) {
                rejections.add(new OrderRejection(60 + i, IdempotencyKey.of(d0, excluded, Side.BUY, i + 1), excluded,
                        "RISK", "BLACKLIST", "제외 종목"));
            }
        }
        BigDecimal ret = BigDecimal.valueOf(equity - seed).divide(BigDecimal.valueOf(seed), 6, java.math.RoundingMode.HALF_EVEN);
        RunMetrics metrics = new RunMetrics(Money.of(equity, Money.KRW), ret, ret.multiply(BigDecimal.valueOf(3)),
                new BigDecimal("0.012345"), dailyStep > 0 ? new BigDecimal("1.8") : null, new BigDecimal("0.5"),
                dailyStep > 0 ? new BigDecimal("1.45") : new BigDecimal("0.7"), new BigDecimal("1.5"), fills,
                trips.size(), new BigDecimal("0.8"), Money.of(5000, Money.KRW), new BigDecimal("0.1"),
                new BigDecimal("0.01"), ret.subtract(new BigDecimal("0.01")));
        RunResult summary = new RunResult(runId, new AccountId(runId), profile.profileHash(), 11L, RunStatus.COMPLETED,
                Money.of(equity, Money.KRW), "", metrics);
        return new RunReport(summary, profile, applied, curve, trips, rejections);
    }
}
