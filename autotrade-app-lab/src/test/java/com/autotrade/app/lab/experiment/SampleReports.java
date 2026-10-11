package com.autotrade.app.lab.experiment;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
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
