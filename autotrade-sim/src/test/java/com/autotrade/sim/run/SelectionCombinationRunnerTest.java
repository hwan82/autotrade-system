package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.selection.DailySelection;
import com.autotrade.sim.selection.SelectionSource;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureStrategies;
import com.autotrade.sim.testing.TestKrxRules;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 조합 실행(STR-17, SIM-09·10, ADR-007 2.4·2.7 실험실 쪽): 날짜별 선정 결과 공유, 선정 밖 신규 진입 차단, 조합별 독립 시드,
 * 선정 없는 조합은 조합 도입 전과 같은 결과. 시세는 골든 테스트와 같은 고정 녹화 이틀(2026-03-03·04)이다.
 */
class SelectionCombinationRunnerTest {

    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");
    private static final LocalDate D1 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 4);
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final List<MarketEvent> EVENTS =
            FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, TestKrxRules.INSTANCE.zone());
    private static final MarketDataSet DATA = new InMemoryMarketDataSet("FIXTURE-KRX-GOLDEN-1", Market.KRX, EVENTS);

    private static final StrategyProfile MKT_HOLD = profile("mkt-hold", "symbol", "005930", "qty", "100");
    private static final StrategyProfile LIMIT_MAKER = profile("limit-maker",
            "symbol", "005930", "qty", "100", "buy-price", "70000", "sell-price", "70500");
    /** 삼성전자를 고르는 선정. */
    private static final SelectionProfile PICK_SAMSUNG = selection("pick-samsung", "5");
    /** 하이닉스만 고르는 선정(삼성전자 진입은 막힌다). */
    private static final SelectionProfile PICK_HYNIX = selection("pick-hynix", "5");

    @Test
    void 선정_없는_조합은_조합_도입_전과_같은_보고서를_낸다() {
        ExperimentSpec legacy = ExperimentSpec.ofProfiles(new ExperimentId("EXP-SEL"), D1, D2, 7,
                Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, List.of(MKT_HOLD));
        ExperimentSpec combos = spec(List.of(Combination.of(MKT_HOLD)));
        String before = ReportWriter.render(runner(1, null).runDetailed(legacy));
        assertThat(ReportWriter.render(runner(1, new CountingSource()).runDetailed(combos))).isEqualTo(before);
        assertThat(before).doesNotContain("selection");
        RunResult r = runner(1, null).runDetailed(legacy).result().runs().get(0);
        assertThat(r.selectionHash()).isEqualTo("none");
        assertThat(r.runSeedScheme()).isEqualTo("run-seed-v1");
        assertThat(r.runSeed()).isEqualTo(RunSeeds.derive(7, MKT_HOLD.profileHash()));
    }

    @Test
    void 같은_전략이라도_선정이_다르면_시드가_다르고_선정_밖_신규_진입은_막힌다() {
        CountingSource source = new CountingSource();
        SimulationReport r = runner(2, source).runDetailed(spec(List.of(
                Combination.of(MKT_HOLD), Combination.of(PICK_SAMSUNG, MKT_HOLD), Combination.of(PICK_HYNIX, MKT_HOLD))));
        RunReport none = r.runs().get(0);
        RunReport samsung = r.runs().get(1);
        RunReport hynix = r.runs().get(2);

        assertThat(r.result().runs()).extracting(RunResult::runSeed).doesNotHaveDuplicates();
        assertThat(r.result().runs()).extracting(RunResult::runSeedScheme)
                .containsExactly("run-seed-v1", "run-seed-v2", "run-seed-v2");
        assertThat(samsung.summary().combinationHash())
                .isEqualTo(Combination.of(PICK_SAMSUNG, MKT_HOLD).combinationHash());
        assertThat(samsung.summary().runSeed())
                .isEqualTo(RunSeeds.derive(7, Combination.of(PICK_SAMSUNG, MKT_HOLD)));

        // 삼성전자를 고른 선정은 선정 없음과 같은 체결(전략이 같고 결정론적)
        assertThat(trades(samsung)).isEqualTo(trades(none)).isNotEmpty();
        assertThat(samsung.selectionDays()).extracting(d -> d.selection().validity()).containsExactly("VALID", "VALID");
        assertThat(samsung.selectionDays()).allSatisfy(d -> assertThat(d.blockedEntries()).isZero());

        // 하이닉스만 고른 선정은 삼성전자 진입을 내지 않는다. 주문을 만들지 않으므로 거절 기록·멱등 키도 없다
        assertThat(hynix.fills()).isEmpty();
        assertThat(hynix.rejections()).isEmpty();
        assertThat(hynix.selectionDays().get(0).blockedEntries()).isEqualTo(1);
        assertThat(hynix.summary().finalEquity()).isEqualTo(Money.of(10_000_000, Money.KRW));
        assertThat(none.selectionDays()).isEmpty();
    }

    @Test
    void 같은_선정을_쓰는_계좌들은_날짜별_결과를_공유하고_공급자는_선정_거래일마다_한_번만_불린다() {
        CountingSource source = new CountingSource();
        SimulationReport r = runner(3, source).runDetailed(spec(List.of(
                Combination.of(PICK_SAMSUNG, MKT_HOLD), Combination.of(PICK_SAMSUNG, LIMIT_MAKER),
                Combination.of(PICK_HYNIX, MKT_HOLD))));
        // 선정 2개 × 거래일 2개
        assertThat(source.calls).hasSize(4).containsExactlyInAnyOrder(
                "pick-samsung@" + D1, "pick-samsung@" + D2, "pick-hynix@" + D1, "pick-hynix@" + D2);
        assertThat(r.runs().get(0).selectionDays()).extracting(d -> d.selection())
                .isEqualTo(r.runs().get(1).selectionDays().stream().map(RunSelectionDay::selection).toList());
        assertThat(r.runs().get(0).selectionDays().get(0).selection().resultHash())
                .isEqualTo(CountingSource.resultHash(PICK_SAMSUNG, D1));
    }

    @Test
    void 결과가_없는_날은_신규_진입만_막고_청산은_그대로_한다() {
        // 1일차는 삼성전자 선정, 2일차는 결과 없음: 1일차 매수, 2일차 매도(EXIT)는 막지 않는다
        SelectionSource firstDayOnly = (s, d) -> d.equals(D1) ? CountingSource.selected(s, d, Set.of(SAMSUNG))
                : new DailySelection.Unavailable(SelectionInvalidReason.MISSING, "");
        SimulationReport r = runner(1, firstDayOnly).runDetailed(spec(List.of(
                Combination.of(MKT_HOLD), Combination.of(PICK_SAMSUNG, MKT_HOLD))));
        assertThat(trades(r.runs().get(1))).isEqualTo(trades(r.runs().get(0)));
        assertThat(r.runs().get(1).fills()).extracting(a -> a.fill().intent()).contains(OrderIntent.EXIT);
        assertThat(r.runs().get(1).selectionDays()).extracting(d -> d.selection().validity())
                .containsExactly("VALID", "MISSING");

        // 1일차부터 결과가 없으면 진입이 없다. 공급자 예외는 그 날만 PRODUCER_FAILED로 남고 Run은 완료된다
        SelectionSource broken = (s, d) -> {
            throw new IllegalStateException("스냅샷 손상");
        };
        RunReport run = runner(1, broken).runDetailed(spec(List.of(Combination.of(PICK_SAMSUNG, MKT_HOLD)))).runs().get(0);
        assertThat(run.summary().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.fills()).isEmpty();
        assertThat(run.selectionDays()).extracting(d -> d.selection().validity())
                .containsExactly("PRODUCER_FAILED", "PRODUCER_FAILED");
    }

    @Test
    void 두_번_돌려도_스레드_수를_바꿔도_결과가_같고_보고서에_조합과_날짜별_선정이_남는다() {
        ExperimentSpec spec = spec(List.of(Combination.of(MKT_HOLD), Combination.of(PICK_SAMSUNG, MKT_HOLD),
                Combination.of(PICK_HYNIX, MKT_HOLD), Combination.of(PICK_SAMSUNG, LIMIT_MAKER)));
        String once = ReportWriter.render(runner(1, new CountingSource()).runDetailed(spec));
        assertThat(ReportWriter.render(runner(1, new CountingSource()).runDetailed(spec))).isEqualTo(once);
        assertThat(ReportWriter.render(runner(4, new CountingSource()).runDetailed(spec))).isEqualTo(once);
        Combination c = Combination.of(PICK_SAMSUNG, MKT_HOLD);
        assertThat(once).contains(" selection=pick-samsung@1 selection-hash=" + PICK_SAMSUNG.selectionHash()
                        + " combination=" + c.combinationHash() + " seed-scheme=run-seed-v2")
                .contains("  selection-day 2026-03-03 VALID result=" + CountingSource.resultHash(PICK_SAMSUNG, D1)
                        + " symbols=1 blocked-entries=0")
                .contains("  selection-day 2026-03-03 VALID result=" + CountingSource.resultHash(PICK_HYNIX, D1)
                        + " symbols=1 blocked-entries=1");
    }

    @Test
    void 선정_있는_조합에_공급자가_없으면_시작하지_않는다() {
        assertThatThrownBy(() -> runner(1, null).runDetailed(spec(List.of(Combination.of(PICK_SAMSUNG, MKT_HOLD)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("선정 결과 공급자");
    }

    // ---- 도우미 ----

    /** 체결을 계좌와 무관한 값(키·의도·수량·가격·이벤트 순번)으로 비교한다. 로트 ID에는 계좌 번호가 들어간다. */
    private static List<String> trades(RunReport run) {
        List<String> out = new ArrayList<>();
        for (AppliedFill a : run.fills()) {
            var f = a.fill();
            out.add(f.key() + " " + f.intent() + " " + f.quantity() + "@" + f.price().toPlainString() + " "
                    + f.signalEventSeq() + ">" + f.fillEventSeq());
        }
        return out;
    }

    /** 선정 ID가 pick-samsung이면 삼성전자, pick-hynix면 하이닉스를 그날 하루 고른다. 호출을 기록한다. */
    static final class CountingSource implements SelectionSource {
        final List<String> calls = new ArrayList<>();

        @Override
        public DailySelection select(SelectionProfile selection, LocalDate date) {
            calls.add(selection.id() + "@" + date);
            return selected(selection, date, Set.of(selection.id().equals("pick-samsung") ? SAMSUNG : HYNIX));
        }

        static DailySelection.Selected selected(SelectionProfile s, LocalDate d, Set<Symbol> symbols) {
            return new DailySelection.Selected(s.selectionHash(), resultHash(s, d), d, d, new TreeSet<>(symbols));
        }

        static String resultHash(SelectionProfile s, LocalDate d) {
            byte[] b = new byte[32];
            byte[] seed = (s.id() + d).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < seed.length; i++) {
                b[i % 32] ^= seed[i];
            }
            return HexFormat.of().formatHex(b);
        }
    }

    private static SimulationExperimentRunner runner(int parallelism, SelectionSource source) {
        return new SimulationExperimentRunner(DATA, TestKrxRules.INSTANCE, COST, new FixtureStrategies(),
                RiskChain.builder(BlacklistSnapshot.EMPTY).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), parallelism, SAMSUNG, source);
    }

    private static ExperimentSpec spec(List<Combination> combinations) {
        return new ExperimentSpec(new ExperimentId("EXP-SEL"), D1, D2, 7, Money.of(10_000_000, Money.KRW), COST.id(),
                DATA.version(), ClockMode.REPLAY, combinations);
    }

    private static SelectionProfile selection(String id, String maxSymbols) {
        return new SelectionProfile(id, 1, Market.KRX, TradingStyle.INTRADAY, new TreeMap<>(Map.of(
                "hypothesis", "테스트", "refresh", "PRE_OPEN_DAILY", "max-symbols", maxSymbols)));
    }

    private static StrategyProfile profile(String id, String... kv) {
        TreeMap<String, String> params = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new StrategyProfile(id, 1, Market.KRX, TradingStyle.INTRADAY, params);
    }
}
