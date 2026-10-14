package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureStrategies;
import com.autotrade.sim.testing.TestKrxRules;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * CFG-06 ① "실행 중 Run 파라미터 변경 시도 실패 테스트"(D-081 1단계 후속, 1단계 QA 판정 권고 R4).
 * 전략·선정 프로필은 변경을 새 프로필 버전으로만 하고, 실행 중 Run이 쓰는 파라미터는 바뀌지 않는다(CFG-06, SIM-10).
 * 여기서는 바꾸려는 시도가 예외로 실패하고, 실패가 그 Run에만 머물며, 결과에 남는 해시가 시작 때 값과 같은지 본다.
 */
class RunParametersImmutableTest {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 3);
    private static final LocalDate TO = LocalDate.of(2026, 3, 4);
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final List<MarketEvent> EVENTS =
            FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, TestKrxRules.INSTANCE.zone());
    private static final MarketDataSet DATA = new InMemoryMarketDataSet("FIXTURE-KRX-GOLDEN-1", Market.KRX, EVENTS);

    @Test
    void 프로필_파라미터는_만든_뒤_바꿀_수_없다() {
        TreeMap<String, String> source = new TreeMap<>(Map.of("symbol", "005930", "qty", "100"));
        StrategyProfile p = new StrategyProfile("mkt-hold", 1, Market.KRX, TradingStyle.INTRADAY, source);
        String hash = p.profileHash();

        assertThatThrownBy(() -> p.parameters().put("qty", "999")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.parameters().remove("qty")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.parameters().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.parameters().entrySet().iterator().next().setValue("999"))
                .isInstanceOf(UnsupportedOperationException.class);
        // 만들 때 넘긴 맵을 나중에 바꿔도 프로필은 복사본을 들고 있다
        source.put("qty", "999");
        assertThat(p.parameters()).containsEntry("qty", "100");
        assertThat(p.profileHash()).isEqualTo(hash);

        TreeMap<String, String> selSource = new TreeMap<>(Map.of(
                SelectionProfile.HYPOTHESIS, "가설", SelectionProfile.REFRESH, "POST_CLOSE_DAILY", SelectionProfile.MAX_SYMBOLS, "5"));
        SelectionProfile s = new SelectionProfile("sel-a", 1, Market.KRX, TradingStyle.SWING, selSource);
        String selHash = s.selectionHash();
        assertThatThrownBy(() -> s.parameters().put(SelectionProfile.MAX_SYMBOLS, "50"))
                .isInstanceOf(UnsupportedOperationException.class);
        selSource.put(SelectionProfile.MAX_SYMBOLS, "50");
        assertThat(s.selectionHash()).isEqualTo(selHash);
    }

    @Test
    void 실험에_등록한_조합_목록은_만든_뒤_바꿀_수_없다() {
        StrategyProfile hold = profile("mkt-hold", "symbol", "005930", "qty", "100");
        List<StrategyProfile> source = new ArrayList<>(List.of(hold));
        ExperimentSpec spec = spec(source);

        assertThatThrownBy(() -> spec.combinations().add(Combination.of(profile("probe", "symbol", "000660"))))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.combinations().set(0, Combination.of(profile("probe", "symbol", "000660"))))
                .isInstanceOf(UnsupportedOperationException.class);
        source.add(profile("probe", "symbol", "000660", "trigger", "005930"));
        assertThat(spec.combinations()).hasSize(1);
    }

    @Test
    void 실행_중_Run이_파라미터를_바꾸려_하면_그_Run만_실패하고_해시는_시작_때와_같다() {
        StrategyProfile hold = profile("mkt-hold", "symbol", "005930", "qty", "100");
        StrategyProfile tamper = profile("tamper", "qty", "100");
        String tamperHash = tamper.profileHash();
        StrategyFactory fixtures = new FixtureStrategies();
        // 실행 중 첫 이벤트에서 자기 프로필의 파라미터를 바꾸려는 전략
        StrategyFactory factory = (profile, runSeed) -> profile.id().equals("tamper")
                ? (Strategy) (event, ctx) -> {
                    profile.parameters().put("qty", "999");
                    return List.of();
                }
                : fixtures.create(profile, runSeed);

        List<RunResult> alone = runner(fixtures).run(spec(List.of(hold))).runs();
        List<RunResult> runs = runner(factory).run(spec(List.of(hold, tamper))).runs();

        assertThat(runs.get(1).status()).isEqualTo(RunStatus.FAILED);
        assertThat(runs.get(1).failureReason()).contains("UnsupportedOperationException");
        assertThat(runs.get(1).profileHash()).isEqualTo(tamperHash);
        assertThat(tamper.parameters()).containsExactlyEntriesOf(Map.of("qty", "100"));
        assertThat(tamper.profileHash()).isEqualTo(tamperHash);
        // 다른 계좌는 영향받지 않는다(계좌 격리, SIM-08)
        assertThat(runs.get(0).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runs.get(0).profileHash()).isEqualTo(hold.profileHash());
        assertThat(runs.get(0).finalEquity()).isEqualTo(alone.get(0).finalEquity());
        assertThat(runs.get(0).metrics()).isEqualTo(alone.get(0).metrics());
    }

    private static SimulationExperimentRunner runner(StrategyFactory factory) {
        return new SimulationExperimentRunner(DATA, TestKrxRules.INSTANCE, COST, factory,
                RiskChain.builder(BlacklistSnapshot.EMPTY).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, Symbol.krx("005930"));
    }

    private static ExperimentSpec spec(List<StrategyProfile> profiles) {
        return ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261014-IMMU"), FROM, TO, 7,
                Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, profiles);
    }

    private static StrategyProfile profile(String id, String... kv) {
        TreeMap<String, String> params = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new StrategyProfile(id, 1, Market.KRX, TradingStyle.INTRADAY, params);
    }
}
