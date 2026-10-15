package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 실험 진행 보고·취소·병렬 상한(SIM-14, D-089). 셋 다 결과를 바꾸지 않아야 한다(지침 5장 결정론): 같은 데이터·프로필·시드면
 * 진행 보고를 받든 안 받든, 병렬 상한이 몇이든 보고서가 골든 스냅샷과 바이트로 같다.
 */
class RunProgressTest {

    private static final String GOLDEN = "/golden/krx-golden.report";
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");
    private static final LocalDate FROM = LocalDate.of(2026, 3, 3);
    private static final LocalDate TO = LocalDate.of(2026, 3, 4);
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final List<MarketEvent> EVENTS =
            FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, TestKrxRules.INSTANCE.zone());
    private static final MarketDataSet DATA = new InMemoryMarketDataSet("FIXTURE-KRX-GOLDEN-1", Market.KRX, EVENTS);
    private static final StrategyProfile MKT_HOLD = profile("mkt-hold", "symbol", "005930", "qty", "100");
    private static final StrategyProfile LIMIT_MAKER = profile("limit-maker",
            "symbol", "005930", "qty", "100", "buy-price", "70000", "sell-price", "70500");
    private static final StrategyProfile PROBE = profile("probe", "symbol", "000660", "trigger", "005930");
    private static final StrategyProfile BOOM = profile("boom", "at", "5");
    private static final List<StrategyProfile> GOLDEN_PROFILES = List.of(MKT_HOLD, LIMIT_MAKER, PROBE);

    /** 진행 상태를 모으고 병렬 상한을 주는 문. */
    private static final class Recorder implements RunGate {

        final List<ExperimentProgress> seen = Collections.synchronizedList(new ArrayList<>());
        final int cap;

        Recorder(int cap) {
            this.cap = cap;
        }

        @Override
        public Optional<String> stopReason() {
            return Optional.empty();
        }

        @Override
        public void onProgress(ExperimentProgress progress) {
            seen.add(progress);
        }

        @Override
        public int maxParallelRuns() {
            return cap;
        }
    }

    @Test
    void 진행_보고와_병렬_상한은_골든_결과를_바꾸지_않는다() throws IOException {
        String golden = read(GOLDEN);
        for (int cap : new int[] {1, 2, 64}) {
            Recorder r = new Recorder(cap);
            String actual = ReportWriter.render(runner(4, r).runDetailed(spec(GOLDEN_PROFILES)));
            assertThat(actual).as("상한 %d", cap).isEqualTo(golden);
            assertThat(r.seen).isNotEmpty();
        }
    }

    @Test
    void 진행_값은_뒤로_가지_않고_끝에는_모든_Run과_거래일을_센다() {
        Recorder r = new Recorder(64);
        runner(3, r).runDetailed(spec(GOLDEN_PROFILES));

        List<ExperimentProgress> seen = List.copyOf(r.seen);
        assertThat(seen.get(0).phase()).isEqualTo(ExperimentProgress.Phase.STARTED);
        assertThat(seen.get(0).runsStarted()).isZero();
        ExperimentProgress last = seen.get(seen.size() - 1);
        assertThat(last.phase()).isEqualTo(ExperimentProgress.Phase.FINISHED);
        assertThat(last.experimentId()).isEqualTo("EXP-20261008-GOLD");
        assertThat(last.runsTotal()).isEqualTo(3);
        assertThat(last.runsFinished()).isEqualTo(3);
        assertThat(last.runsFailed()).isZero();
        assertThat(last.daysPerRun()).isEqualTo(2); // 3/3, 3/4
        assertThat(last.daysDone()).isEqualTo(last.daysTotal()).isEqualTo(6);
        assertThat(last.permille()).isEqualTo(1000);
        for (int i = 1; i < seen.size(); i++) {
            ExperimentProgress a = seen.get(i - 1);
            ExperimentProgress b = seen.get(i);
            assertThat(b.daysDone()).isGreaterThanOrEqualTo(a.daysDone());
            assertThat(b.runsStarted()).isGreaterThanOrEqualTo(a.runsStarted());
            assertThat(b.runsFinished()).isGreaterThanOrEqualTo(a.runsFinished());
        }
        // 실험 시작 1 + Run마다 (시작 1 + 거래일 바뀜 1 + 끝 1) + 실험 끝 1
        assertThat(seen).hasSize(1 + 3 * 3 + 1);
    }

    @Test
    void 병렬_상한_1이면_한_번에_Run_하나만_돈다() {
        Recorder r = new Recorder(1);
        runner(8, r).runDetailed(spec(GOLDEN_PROFILES));

        assertThat(r.seen).allSatisfy(p -> assertThat(p.runsStarted() - p.runsFinished()).isLessThanOrEqualTo(1));
    }

    @Test
    void 실패한_Run도_끝난_Run으로_세고_남은_거래일을_채운다() {
        Recorder r = new Recorder(64);
        runner(2, r).runDetailed(spec(List.of(MKT_HOLD, BOOM)));

        ExperimentProgress last = r.seen.get(r.seen.size() - 1);
        assertThat(last.runsFinished()).isEqualTo(2);
        assertThat(last.runsFailed()).isEqualTo(1);
        assertThat(last.daysDone()).isEqualTo(last.daysTotal());
    }

    @Test
    void 진행_보고를_받는_쪽이_예외를_던져도_실험은_끝까지_돌고_결과가_같다() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RunGate failing = new RunGate() {
            @Override
            public Optional<String> stopReason() {
                return Optional.empty();
            }

            @Override
            public void onProgress(ExperimentProgress progress) {
                calls.incrementAndGet();
                throw new IllegalStateException("상태 파일 쓰기 실패");
            }
        };

        assertThat(ReportWriter.render(runner(2, failing).runDetailed(spec(GOLDEN_PROFILES)))).isEqualTo(read(GOLDEN));
        assertThat(calls.get()).isEqualTo(1); // 첫 실패 뒤 그 실험의 보고는 끈다
    }

    @Test
    void 취소는_취소_예외로_올라오고_종료_코드는_3이다() {
        AtomicInteger asked = new AtomicInteger();
        RunGate cancelLater = new RunGate() {
            @Override
            public Optional<String> stopReason() {
                return Optional.empty();
            }

            @Override
            public void check() {
                if (asked.incrementAndGet() >= 3) { // 실험 시작·Run 시작은 통과, 첫 거래일 마감에서 취소
                    throw new ExperimentCancelledException("실험실에서 취소");
                }
            }
        };

        assertThatThrownBy(() -> runner(1, cancelLater).runDetailed(spec(GOLDEN_PROFILES)))
                .isInstanceOfSatisfying(ExperimentCancelledException.class,
                        e -> assertThat(e.exitCode()).isEqualTo(3))
                .hasMessage("실험실에서 취소");
        assertThat(new ExperimentStoppedException("x").exitCode()).isEqualTo(4);
    }

    @Test
    void 병렬_상한이_범위_밖이면_실행기를_만들지_않는다() {
        for (int cap : new int[] {0, 65}) {
            assertThatThrownBy(() -> runner(1, new Recorder(cap)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxParallelRuns");
        }
    }

    private static SimulationExperimentRunner runner(int parallelism, RunGate gate) {
        return new SimulationExperimentRunner(DATA, TestKrxRules.INSTANCE, COST, new FixtureStrategies(),
                RiskChain.builder(BlacklistSnapshot.of(List.of(new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "테스트"))))
                        .build(),
                ExecutionSettings.defaults(), new CodeVersion("test", false), parallelism, SAMSUNG, null, gate);
    }

    private static ExperimentSpec spec(List<StrategyProfile> profiles) {
        return ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD"), FROM, TO, 7,
                Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, profiles);
    }

    private static StrategyProfile profile(String id, String... kv) {
        TreeMap<String, String> params = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new StrategyProfile(id, 1, Market.KRX, TradingStyle.INTRADAY, params);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = RunProgressTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
