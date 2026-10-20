package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.domain.order.Side;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.marketdata.rules.BundledMarketRules;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.selection.DailySelection;
import com.autotrade.sim.selection.IntradayUniverse;
import com.autotrade.sim.selection.SelectionSource;
import com.autotrade.sim.testing.TestKrxRules;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * F7(D-130, ADR-013): 선정 계좌에서 후보 밖이라 막힌 신규 진입 신호가 전략의 하루 진입 수·대기 진입 금액을 쓰지 않는다.
 * 실제 카탈로그 전략({@code random}, {@code ManagedStrategy})을 실행기에 올려 본다. 후보 밖 종목(삼성전자)의 신호가 먼저
 * 오고, 후보(하이닉스)의 신호가 뒤에 온다. 하루 진입 수 상한은 1이다.
 */
class SelectionEntryQuotaTest {

    private static final Symbol OUTSIDE = Symbol.krx("005930");
    private static final Symbol PICKED = Symbol.krx("000660");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 3);
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final StrategyProfile RANDOM_ONE_PER_DAY = randomProfile();
    private static final SelectionProfile PICK = new SelectionProfile("pick-hynix", 1, Market.KRX, TradingStyle.INTRADAY,
            new TreeMap<>(Map.of("hypothesis", "테스트", "refresh", "PRE_OPEN_DAILY", "max-symbols", "5")));

    @Test
    void 후보_밖_신호가_먼저_와도_후보_종목이_그날_진입_한도_안에서_들어간다() {
        RunReport run = run(Combination.of(PICK, RANDOM_ONE_PER_DAY));

        // 고치기 전: 삼성전자 신호가 하루 1건 한도와 대기 진입 금액을 쓰고 실행기가 버려서 하이닉스 매수가 0건이었다
        assertThat(buys(run)).extracting(f -> f.fill().symbol()).containsExactly(PICKED);
        assertThat(run.selectionDays()).hasSize(1);
        assertThat(run.selectionDays().get(0).blockedEntries()).as("막힌 삼성전자 신호는 실행기가 한 번씩 센다").isEqualTo(2);
        assertThat(run.rejections()).isEmpty();
    }

    @Test
    void 장중_순위_시각_전_신호는_하루_진입_한도를_쓰지_않고_순위_뒤_후보가_들어간다() {
        // 순위 입력 범위는 실제 KRX 장 시간(시험용 TestKrxRules는 늘 정규장이라 첫 이벤트를 늦은 시작으로 봄).
        // PR #205 QA Q1: 09:30 순위 전 삼성전자 신호(그 종목도 순위 뒤에는 후보)가 하루 1건 한도를 쓰면 하이닉스가 못 들어간다.
        // 실행기가 이벤트 순번을 보지 않고 답하면(QA 변이 P1) 순위 전 신호를 받아 한도를 쓰고 주문 전 거름이 버려 매수 0건이 된다
        SelectionProfile openAll = new SelectionProfile("open-all", 1, Market.KRX, TradingStyle.INTRADAY,
                new TreeMap<>(Map.of("hypothesis", "테스트", "refresh", "INTRADAY_AT", "at", "09:30", "max-symbols", "200",
                        "gate.01.metric", "session-turnover", "gate.01.op", "GT", "gate.01.value", "0")));
        List<MarketEvent> events = new ArrayList<>();
        long[] seq = {0};
        trade(events, seq, PICKED, "09:00:00", 180_100);
        book(events, seq, OUTSIDE, "09:10:00", 70_000, 70_100);
        trade(events, seq, OUTSIDE, "09:10:01", 70_100);
        book(events, seq, PICKED, "09:31:00", 180_000, 180_100);
        trade(events, seq, PICKED, "09:31:01", 180_100);
        book(events, seq, PICKED, "09:31:02", 180_000, 180_100);
        SimulationReport report = runner(events, new CatalogStrategyFactory(TestKrxRules.INSTANCE),
                RiskChain.builder(BlacklistSnapshot.EMPTY).build())
                .withIntradayUniverse(new IntradayUniverse(Set.of(OUTSIDE.code(), PICKED.code()), Set.of(),
                        BundledMarketRules.of(Market.KRX)))
                .runDetailed(spec(Combination.of(openAll, RANDOM_ONE_PER_DAY), events));
        RunReport run = report.runs().get(0);

        assertThat(buys(run)).extracting(f -> f.fill().symbol()).containsExactly(PICKED);
        assertThat(run.selectionDays()).singleElement()
                .satisfies(d -> assertThat(d.blockedEntries()).as("순위 전 삼성전자 신호 1건").isEqualTo(1));
    }

    @Test
    void 블랙리스트에_오른_후보_종목은_예전처럼_위험_검사에서_거절된다() {
        // 거름은 선정만 앞당긴다. 블랙리스트는 그대로 주문 전 위험 검사(RiskChain)가 거절한다(절대 규칙 3)
        RiskChain black = RiskChain.builder(BlacklistSnapshot.of(
                List.of(new BlacklistEntry(PICKED, BlacklistType.MANUAL, "테스트")))).build();
        RunReport run = runDetailed(Combination.of(PICK, RANDOM_ONE_PER_DAY),
                new CatalogStrategyFactory(TestKrxRules.INSTANCE), black).runs().get(0);

        assertThat(run.fills()).isEmpty();
        assertThat(run.rejections()).isNotEmpty().allSatisfy(x -> assertThat(x.symbol()).isEqualTo(PICKED));
    }

    @Test
    void 선정_없는_계좌는_예전처럼_먼저_온_종목이_한도를_쓴다() {
        RunReport run = run(Combination.of(RANDOM_ONE_PER_DAY));

        assertThat(buys(run)).extracting(f -> f.fill().symbol()).containsExactly(OUTSIDE);
        assertThat(run.selectionDays()).isEmpty();
    }

    @Test
    void 선정_없는_계좌의_보고서는_admitsEntry_도입_전_문맥과_바이트_단위로_같다() {
        // 도입 전 문맥 = admitsEntry를 구현하지 않은 문맥(기본값 true). 실제 카탈로그 전략 여러 프로필로 본다
        for (StrategyProfile profile : List.of(RANDOM_ONE_PER_DAY, randomProfile("6"))) {
            Combination none = Combination.of(profile);
            SimulationReport current = runDetailed(none, new CatalogStrategyFactory(TestKrxRules.INSTANCE));
            assertThat(current.runs().get(0).fills()).as("체결이 있는 Run으로 비교").isNotEmpty();
            assertThat(ReportWriter.render(current))
                    .isEqualTo(ReportWriter.render(runDetailed(none, new LegacyContextFactory())));
        }
    }

    /** admitsEntry를 넘기지 않는 문맥으로 카탈로그 전략을 감싼다(D-130 도입 전 실행기와 같은 문맥). */
    private static final class LegacyContextFactory implements StrategyFactory {
        private final CatalogStrategyFactory catalog = new CatalogStrategyFactory(TestKrxRules.INSTANCE);

        @Override
        public Strategy create(StrategyProfile profile, long runSeed) {
            Strategy inner = catalog.create(profile, runSeed);
            return (event, ctx) -> inner.onEvent(event, new StrategyContext() {
                @Override
                public Instant now() {
                    return ctx.now();
                }

                @Override
                public AccountSnapshot account() {
                    return ctx.account();
                }

                @Override
                public boolean hasOpenOrder(Symbol symbol) {
                    return ctx.hasOpenOrder(symbol);
                }
            });
        }
    }

    private static List<AppliedFill> buys(RunReport run) {
        return run.fills().stream().filter(f -> f.fill().side() == Side.BUY).toList();
    }

    private static RunReport run(Combination combination) {
        return runDetailed(combination, new CatalogStrategyFactory(TestKrxRules.INSTANCE)).runs().get(0);
    }

    private static SimulationReport runDetailed(Combination combination, StrategyFactory factory) {
        return runDetailed(combination, factory, RiskChain.builder(BlacklistSnapshot.EMPTY).build());
    }

    private static SimulationReport runDetailed(Combination combination, StrategyFactory factory, RiskChain risk) {
        List<MarketEvent> events = events();
        return runner(events, factory, risk).runDetailed(spec(combination, events));
    }

    private static SimulationExperimentRunner runner(List<MarketEvent> events, StrategyFactory factory, RiskChain risk) {
        SelectionSource source = (selection, date) -> new DailySelection.Selected(selection.selectionHash(),
                "ab".repeat(32), date, date, new TreeSet<>(List.of(PICKED)));
        return new SimulationExperimentRunner(data(events), TestKrxRules.INSTANCE, COST, factory, risk,
                ExecutionSettings.defaults(), new CodeVersion("test", false), 1, OUTSIDE, source);
    }

    private static MarketDataSet data(List<MarketEvent> events) {
        return new InMemoryMarketDataSet("FIXTURE-F7", Market.KRX, events);
    }

    private static ExperimentSpec spec(Combination combination, List<MarketEvent> events) {
        return new ExperimentSpec(new ExperimentId("EXP-F7"), DAY, DAY, 7, Money.of(10_000_000, Money.KRW), COST.id(),
                data(events).version(), ClockMode.REPLAY, List.of(combination));
    }

    /** 삼성전자 호가·체결 두 번(신호 두 번) 뒤 하이닉스 호가·체결, 그 뒤 체결을 받을 호가. */
    private static List<MarketEvent> events() {
        List<MarketEvent> out = new ArrayList<>();
        long[] seq = {0};
        book(out, seq, OUTSIDE, "09:10:00", 70_000, 70_100);
        trade(out, seq, OUTSIDE, "09:10:01", 70_100);
        book(out, seq, OUTSIDE, "09:10:30", 70_000, 70_100);
        trade(out, seq, OUTSIDE, "09:10:31", 70_100);
        book(out, seq, PICKED, "09:11:00", 180_000, 180_100);
        trade(out, seq, PICKED, "09:11:01", 180_100);
        book(out, seq, PICKED, "09:11:02", 180_000, 180_100);
        book(out, seq, OUTSIDE, "09:11:03", 70_000, 70_100);
        trade(out, seq, PICKED, "09:12:00", 180_100);
        book(out, seq, PICKED, "09:12:01", 180_000, 180_100);
        return out;
    }

    private static void book(List<MarketEvent> out, long[] seq, Symbol s, String time, long bid, long ask) {
        Instant t = at(time);
        out.add(new OrderBookSnapshot(s, ++seq[0], t, t, List.of(new PriceLevel(BigDecimal.valueOf(ask), 10_000)),
                List.of(new PriceLevel(BigDecimal.valueOf(bid), 10_000))));
    }

    private static void trade(List<MarketEvent> out, long[] seq, Symbol s, String time, long price) {
        Instant t = at(time);
        out.add(new TradeTick(s, ++seq[0], t, t, BigDecimal.valueOf(price), 10));
    }

    private static Instant at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(TestKrxRules.INSTANCE.zone()).toInstant();
    }

    private static StrategyProfile randomProfile() {
        return randomProfile("1");
    }

    private static StrategyProfile randomProfile(String maxEntriesPerDay) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "F7 시험");
        p.put("logic", "random");
        p.put("signal.per-mille", "1000");
        p.put("entry.window.start", "09:05");
        p.put("entry.window.end", "15:00");
        p.put("entry.expiry-seconds", "600");
        p.put("sizing.risk-per-trade-pct", "0.5");
        p.put("sizing.max-position-pct", "20");
        p.put("sizing.max-positions", "3");
        p.put("sizing.cash-buffer-pct", "5");
        p.put("limits.max-entries-per-day", maxEntriesPerDay);
        p.put("limits.reentry-cooldown-minutes", "0");
        p.put("limits.daily-loss-limit-pct", "2");
        p.put("limits.max-consecutive-stops", "3");
        p.put("exit.stop-loss-pct", "1.5");
        p.put("exit.take-profit-pct", "3");
        p.put("exit.eod", "FLATTEN");
        p.put("exit.eod-time", "15:10");
        return new StrategyProfile("rnd-" + maxEntriesPerDay, 1, Market.KRX, TradingStyle.INTRADAY, p);
    }
}
