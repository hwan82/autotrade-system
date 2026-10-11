package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureStrategies;
import com.autotrade.sim.testing.TestKrxRules;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 실험 정지 확인(D-063, broker-and-fault-design 5.2 0번): Run 시작 전·거래일마다 묻고, 멈추면 결과 없이 실험 전체를 끝낸다. */
class RunGateTest {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 3);
    private static final LocalDate TO = LocalDate.of(2026, 3, 4);
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final List<MarketEvent> EVENTS =
            FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, TestKrxRules.INSTANCE.zone());
    private static final MarketDataSet DATA = new InMemoryMarketDataSet("FIXTURE-KRX-GOLDEN-1", Market.KRX, EVENTS);
    private static final StrategyProfile HOLD = profile("mkt-hold", "symbol", "005930", "qty", "100");
    private static final StrategyProfile MAKER = profile("limit-maker",
            "symbol", "005930", "qty", "100", "buy-price", "70000", "sell-price", "70500");

    @Test
    void 멈추지_않는_문은_결과를_바꾸지_않는다() {
        AtomicInteger asked = new AtomicInteger();
        RunGate open = () -> {
            asked.incrementAndGet();
            return Optional.empty();
        };
        String without = ReportWriter.render(runner(2, RunGate.NONE).runDetailed(spec()));
        String with = ReportWriter.render(runner(2, open).runDetailed(spec()));

        assertThat(with).isEqualTo(without);
        // 시작 1번 + Run 2개 × (시작 1번 + 거래일 바뀜 1번)
        assertThat(asked.get()).isEqualTo(5);
    }

    @Test
    void 시작할_때_멈추라고_하면_아무_Run도_돌지_않는다() {
        AtomicInteger asked = new AtomicInteger();
        RunGate closed = () -> {
            asked.incrementAndGet();
            return Optional.of("정지 요청 파일");
        };

        assertThatThrownBy(() -> runner(2, closed).runDetailed(spec()))
                .isInstanceOf(ExperimentStoppedException.class).hasMessage("정지 요청 파일");
        assertThat(asked.get()).isEqualTo(1);
    }

    @Test
    void Run_도중_멈추라고_하면_Run_실패로_남기지_않고_실험_전체를_끝낸다() {
        AtomicInteger asked = new AtomicInteger();
        // 시작 확인과 Run 시작 확인은 통과하고 거래일이 바뀌는 첫 확인에서 멈춘다
        RunGate closesLater = () -> asked.incrementAndGet() >= 3 ? Optional.of("장 시간 보호 구간") : Optional.empty();

        assertThatThrownBy(() -> runner(1, closesLater).runDetailed(spec()))
                .isInstanceOf(ExperimentStoppedException.class).hasMessage("장 시간 보호 구간");
        // 병렬 1: 첫 Run이 둘째 날에서 멈추면 둘째 Run은 문을 다시 묻지 않고 시작하지 않는다
        assertThat(asked.get()).isEqualTo(3);
    }

    @Test
    void 병렬로_돌던_Run도_다음_거래일에_끝나고_결과_없이_멈춘다() {
        AtomicInteger asked = new AtomicInteger();
        // 처음 세 번(실험 시작 + 확인 2번)은 통과하고 그 뒤로는 멈춘다
        RunGate closesLater = () -> asked.incrementAndGet() > 3 ? Optional.of("정지 요청 파일") : Optional.empty();

        assertThatThrownBy(() -> runner(2, closesLater).runDetailed(spec()))
                .isInstanceOf(ExperimentStoppedException.class).hasMessage("정지 요청 파일");
        // 병렬 2라 두 Run이 함께 돈다. 이미 돌던 Run도 거래일 경계에서 끝나고(예외가 결과 대신 올라옴), 먼저 멈춘 Run이 남긴
        // 예외를 본 Run은 문을 다시 묻지 않고 같은 예외로 끝난다. 그래서 어느 Run의 결과를 먼저 받든 처음 받은 정지 이유가
        // 그대로 올라온다(PR #52 architect R1). 스레드 순서에 따라 네 번째나 다섯 번째 확인에서 끝난다
        assertThat(asked.get()).isBetween(4, 5);
    }

    private static SimulationExperimentRunner runner(int parallelism, RunGate gate) {
        return new SimulationExperimentRunner(DATA, TestKrxRules.INSTANCE, COST, new FixtureStrategies(),
                RiskChain.builder(BlacklistSnapshot.EMPTY).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), parallelism, null, null, gate);
    }

    private static ExperimentSpec spec() {
        return ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261011-GATE"), FROM, TO, 7, Money.of(10_000_000, Money.KRW),
                COST.id(), DATA.version(), ClockMode.REPLAY, List.of(HOLD, MAKER));
    }

    private static StrategyProfile profile(String id, String... kv) {
        TreeMap<String, String> params = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new StrategyProfile(id, 1, Market.KRX, TradingStyle.INTRADAY, params);
    }
}
