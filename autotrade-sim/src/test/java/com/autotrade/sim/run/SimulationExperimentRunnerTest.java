package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
import com.autotrade.risk.RiskChain;
import com.autotrade.risk.RiskDecision;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.recording.RecordedMarketDataSet;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureRecordings;
import com.autotrade.sim.testing.FixtureStrategies;
import com.autotrade.sim.testing.TestKrxRules;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 3단계 완료 기준(TST-02): 고정 녹화 데이터 + 고정 프로필 → 거래 내역·지표가 골든 스냅샷과 정확히 일치.
 * 결정론(SIM-09), 계좌 격리(SIM-08), 블랙리스트 통과(SIM-12의 시뮬레이션 쪽), 실험 시작 검증(SIM-07·10)도 여기서 본다.
 *
 * <p>골든 파일 갱신: {@code ./gradlew :autotrade-sim:test -Dgolden.update=true} 후 diff를 손 계산과 대조해 커밋한다.
 */
class SimulationExperimentRunnerTest {

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

    @Test
    void 골든_스냅샷과_정확히_일치한다() throws IOException {
        String actual = ReportWriter.render(runner(2).runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER, PROBE))));
        if (Boolean.getBoolean("golden.update")) {
            Path p = Path.of("src/test/resources" + GOLDEN);
            Files.createDirectories(p.getParent());
            Files.writeString(p, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(read(GOLDEN));
    }

    /**
     * 3단계 완료 기준(로드맵 6장 "녹화 데이터로 골든 테스트 통과", ADR-004 4장): 같은 이틀치를 2단계 녹화기로 실제 녹화 형식에
     * 쓰고 녹화 어댑터로 읽어 돌리면 같은 골든과 바이트 단위로 같다.
     */
    @Test
    void 녹화_형식으로_다시_돌려도_같은_골든과_일치한다(@TempDir Path root) throws IOException {
        FixtureRecordings.record(root, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        RecordedMarketDataSet recorded = RecordedMarketDataSet.open(new RecordingLayout(root), Market.KRX, FROM, TO);
        assertThat(recorded.days()).extracting(RecordedMarketDataSet.DayInfo::recordedEvents).containsExactly(14L, 3L);

        String actual = ReportWriter.render(runner(recorded, 2).runDetailed(spec(recorded, List.of(MKT_HOLD, LIMIT_MAKER, PROBE))));
        assertThat(actual).isEqualTo(read(GOLDEN));
    }

    @Test
    void 입력_시세의_공백은_보고서에_남고_공백_뒤_같은_가격_체결로는_대기_지정가가_체결되지_않는다() {
        // 골든 1일차 seq 4(대기 시작) 뒤에 005930 끊김을 넣는다. 원래는 seq 6에서 70주가 체결된다
        List<MarketEvent> withGap = new ArrayList<>();
        for (MarketEvent e : EVENTS) {
            withGap.add(e);
            if (e.sequence() == 4) {
                Instant t = e.receivedAt().plusMillis(100);
                withGap.add(new DataGap(SAMSUNG, 0, t, t, e.receivedAt(), t, DataGap.GapReason.DISCONNECTED));
            }
        }
        List<MarketEvent> renumbered = new ArrayList<>();
        for (MarketEvent e : withGap) {
            renumbered.add(FixtureEvents.withSequence(e, renumbered.size() + 1L));
        }
        MarketDataSet data = new InMemoryMarketDataSet("FIXTURE-KRX-GOLDEN-GAP", Market.KRX, renumbered);
        SimulationReport r = runner(data, 1).runDetailed(spec(data, List.of(LIMIT_MAKER)));

        assertThat(r.dataGaps()).singleElement().satisfies(g -> assertThat(g.sequence()).isEqualTo(5));
        assertThat(ReportWriter.render(r)).contains("data-gap seq=5 KRX:005930 DISCONNECTED ");
        // 공백으로 앞선 잔량(350)을 모르게 돼 seq 6·7(70,000 체결)로는 체결되지 않는다. 지정가보다 유리한 69,900 체결 중
        // seq 9는 VI 중이라 막히고, VI 해제 뒤 seq 11(69,900 × 40)에서 처음 40주가 지정가로 체결된다
        assertThat(r.runs().get(0).fills()).first().satisfies(a -> {
            assertThat(a.fill().fillEventSeq()).isEqualTo(11L);
            assertThat(a.fill().quantity()).isEqualTo(40L);
            assertThat(a.fill().price().toPlainString()).isEqualTo("70000");
        });
    }

    @Test
    void 손_계산과_맞는다() {
        SimulationReport r = runner(1).runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER, PROBE)));
        RunReport hold = r.runs().get(0);
        RunReport maker = r.runs().get(1);
        RunReport probe = r.runs().get(2);

        // mkt-hold: 매수 60@70,100 + 40@70,200(수수료 630 + 421), 매도 60@70,900 + 40@70,800
        // (매도 비용 638 + 2,127 + 6,381, 424 + 1,416 + 4,248) → 현금 10,055,715
        assertThat(hold.fills()).extracting(a -> a.fill().price().toPlainString())
                .containsExactly("70100", "70200", "70900", "70800");
        assertThat(hold.summary().finalEquity()).isEqualTo(Money.of(10_055_715, Money.KRW));
        // 1일차 마감: 현금 2,984,949 + 100주 × 마지막 체결가 70,600
        assertThat(hold.equityCurve().get(0).equity()).isEqualTo(Money.of(10_044_949, Money.KRW));
        assertThat(hold.summary().metrics().totalReturn()).isEqualByComparingTo("0.005572");
        assertThat(hold.summary().metrics().totalCosts()).isEqualTo(Money.of(1_051 + 9_146 + 6_088, Money.KRW));

        // limit-maker: 70,000 대기(앞선 잔량 350) → 70주, VI 중 체결 없음, 해제 후 30주 → 70,500 매도
        assertThat(maker.fills()).extracting(a -> a.fill().quantity()).containsExactly(70L, 30L, 100L);
        assertThat(maker.fills()).extracting(a -> a.fill().fillEventSeq()).containsExactly(6L, 10L, 12L);
        assertThat(maker.roundTrips()).singleElement()
                .satisfies(t -> assertThat(t.pnl()).isEqualTo(Money.of(33_793, Money.KRW)));
        assertThat(maker.summary().metrics().winRate()).isEqualByComparingTo("1");

        // probe: 블랙리스트가 거래소보다 먼저 막는다
        assertThat(probe.fills()).isEmpty();
        assertThat(probe.rejections()).singleElement().satisfies(j -> {
            assertThat(j.stage()).isEqualTo("RISK");
            assertThat(j.code()).isEqualTo("BLACKLIST");
        });

        // 벤치마크(005930 매수 후 보유): 70,100 → 70,800
        assertThat(hold.summary().metrics().benchmarkReturn()).isEqualByComparingTo("0.009986");
        assertThat(r.result().costTableHash()).isEqualTo(COST.tableHash(FROM, TO));
        assertThat(r.result().executionModelId()).isEqualTo("BOOK-V6;latency=PT0.5S");
    }

    @Test
    void 모든_체결은_신호_다음_이벤트로_난다() {
        SimulationReport r = runner(1).runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER)));
        for (RunReport run : r.runs()) {
            for (AppliedFill a : run.fills()) {
                assertThat(a.fill().fillEventSeq()).isGreaterThan(a.fill().signalEventSeq());
            }
        }
    }

    @Test
    void 두_번_돌려도_스레드_수를_바꿔도_결과가_같다() {
        ExperimentSpec spec = spec(List.of(MKT_HOLD, LIMIT_MAKER, PROBE));
        String once = ReportWriter.render(runner(1).runDetailed(spec));
        assertThat(ReportWriter.render(runner(1).runDetailed(spec))).isEqualTo(once);
        assertThat(ReportWriter.render(runner(3).runDetailed(spec))).isEqualTo(once);
    }

    @Test
    void 한_계좌의_실패는_그_계좌만_멈추고_다른_계좌_결과를_바꾸지_않는다() {
        SimulationReport alone = runner(1).runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER)));
        SimulationReport withBoom = runner(3).runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER, BOOM)));

        assertThat(withBoom.result().runs()).extracting(rr -> rr.status())
                .containsExactly(RunStatus.COMPLETED, RunStatus.COMPLETED, RunStatus.FAILED);
        assertThat(withBoom.result().runs().get(2).failureReason()).contains("고의 실패");
        assertThat(withBoom.result().runs().get(2).metrics()).isNull();
        for (int i = 0; i < 2; i++) {
            assertThat(withBoom.runs().get(i).fills()).isEqualTo(alone.runs().get(i).fills());
            assertThat(withBoom.runs().get(i).summary()).isEqualTo(alone.runs().get(i).summary());
        }
    }

    @Test
    void 계좌_번호와_시드는_실험_ID와_프로필에서_결정론적으로_나온다() {
        SimulationReport r = runner(2).runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER)));
        assertThat(r.result().runs()).extracting(rr -> rr.runId())
                .containsExactly("EXP-20261008-GOLD-A01", "EXP-20261008-GOLD-A02");
        assertThat(r.result().runs().get(0).accountId().value()).isEqualTo("EXP-20261008-GOLD-A01");
        assertThat(r.result().runs().get(0).runSeed()).isEqualTo(RunSeeds.derive(7, MKT_HOLD.profileHash()));
        assertThat(r.result().runs().get(0).profileHash()).isEqualTo(MKT_HOLD.profileHash());
    }

    @Test
    void 입력이_실험과_맞지_않으면_시작하지_않는다() {
        SimulationExperimentRunner runner = runner(1);
        List<StrategyProfile> ps = List.of(MKT_HOLD);
        assertThatThrownBy(() -> runner.run(ExperimentSpec.ofProfiles(new ExperimentId("EXP-X"), FROM, TO, 7,
                Money.of(10_000_000, Money.KRW), COST.id(), "OTHER-DATA", ClockMode.REPLAY, ps)))
                .hasMessageContaining("데이터 스냅샷");
        assertThatThrownBy(() -> runner.run(ExperimentSpec.ofProfiles(new ExperimentId("EXP-X"), FROM, TO, 7,
                Money.of(10_000_000, Money.KRW), "KR-DEFAULT-000000000000", DATA.version(), ClockMode.REPLAY, ps)))
                .hasMessageContaining("비용 모델 ID");
        // 비용 표가 2010-01-01부터라 2009년이 걸친 실험은 거부(SIM-07)
        assertThatThrownBy(() -> runner.run(ExperimentSpec.ofProfiles(new ExperimentId("EXP-X"), LocalDate.of(2009, 12, 30),
                TO, 7, Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, ps)))
                .hasMessageContaining("비용 표 밖");
        assertThatThrownBy(() -> runner.run(ExperimentSpec.ofProfiles(new ExperimentId("EXP-X"), FROM, TO, 7,
                Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REALTIME, ps)))
                .hasMessageContaining("리플레이");
    }

    @Test
    void 모르는_프로필은_그_Run만_실패한다() {
        StrategyProfile unknown = profile("nope", "x", "1");
        SimulationReport r = runner(1).runDetailed(spec(List.of(MKT_HOLD, unknown)));
        assertThat(r.result().runs()).extracting(rr -> rr.status())
                .containsExactly(RunStatus.COMPLETED, RunStatus.FAILED);
    }

    @Test
    void 진입_규칙은_모든_모의_진입에_적용된다() {
        SimulationExperimentRunner denyAll = new SimulationExperimentRunner(DATA, TestKrxRules.INSTANCE, COST,
                new FixtureStrategies(),
                RiskChain.builder(BlacklistSnapshot.EMPTY).entryRule((o, a) -> RiskDecision.reject("DENY", "테스트")).build(),
                ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, null);
        SimulationReport r = denyAll.runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER)));
        for (RunReport run : r.runs()) {
            assertThat(run.fills()).isEmpty();
            assertThat(run.rejections()).isNotEmpty().allSatisfy(j -> assertThat(j.code()).isEqualTo("DENY"));
            assertThat(run.summary().finalEquity()).isEqualTo(Money.of(10_000_000, Money.KRW));
        }
    }

    /**
     * D-115: 목록이 아닌 입력({@link MarketEventStream})은 모든 Run을 한 번 읽기에 함께 진행한다. Run별 결과와 상세 보고서가
     * 목록 입력과 바이트 단위로 같아야 한다(골든, 계좌 실패 격리, 기간 자르기, 선정 없음).
     */
    @Test
    void 목록이_아닌_입력도_같은_골든과_일치한다() throws IOException {
        Streamed streamed = new Streamed(DATA);
        String actual = ReportWriter.render(runner(streamed, 2).runDetailed(spec(List.of(MKT_HOLD, LIMIT_MAKER, PROBE))));
        assertThat(actual).isEqualTo(read(GOLDEN));
        // 두 번 읽는다(순서·기간 확인, 그다음 모든 Run 함께 진행). 이벤트 수와 상관없이 목록을 만들지 않는다
        assertThat(streamed.reads).isEqualTo(2);
    }

    @Test
    void 녹화를_날마다_다시_읽는_입력도_같은_골든과_일치한다(@TempDir Path root) throws IOException {
        FixtureRecordings.record(root, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        RecordingLayout layout = new RecordingLayout(root);
        List<Path> dirs = List.of(layout.dayDir(Market.KRX, FROM), layout.dayDir(Market.KRX, TO));
        int[] dayChecks = {0};
        StreamedRecordingDataSet streamed = StreamedRecordingDataSet.open(Market.KRX, dirs, () -> dayChecks[0]++, e -> { });
        assertThat(streamed.version()).isEqualTo(RecordedMarketDataSet.fromDirs(Market.KRX, dirs).version());

        String actual = ReportWriter.render(runner(streamed, 2).runDetailed(spec(streamed, List.of(MKT_HOLD, LIMIT_MAKER, PROBE))));
        assertThat(actual).isEqualTo(read(GOLDEN));
        assertThat(dayChecks[0]).as("열 때 한 번, 실행기가 두 번 읽을 때 날마다 정지 확인").isEqualTo(6);
    }

    @Test
    void 목록이_아닌_입력에서도_한_계좌의_실패는_그_계좌만_멈춘다() {
        ExperimentSpec spec = spec(List.of(MKT_HOLD, BOOM, LIMIT_MAKER, profile("nope", "x", "1")));
        SimulationReport list = runner(3).runDetailed(spec);
        SimulationReport streamed = runner(new Streamed(DATA), 1).runDetailed(spec);

        assertThat(streamed.result().runs()).extracting(rr -> rr.status())
                .containsExactly(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.COMPLETED, RunStatus.FAILED);
        assertThat(ReportWriter.render(streamed)).isEqualTo(ReportWriter.render(list));
    }

    @Test
    void 목록이_아닌_입력도_실험_기간_밖_이벤트를_같게_뺀다() {
        for (LocalDate[] range : new LocalDate[][] {{FROM, FROM}, {TO, TO}, {TO.plusDays(1), TO.plusDays(3)}}) {
            ExperimentSpec spec = ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD"), range[0], range[1], 7,
                    Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY,
                    List.of(MKT_HOLD, LIMIT_MAKER, PROBE));
            assertThat(ReportWriter.render(runner(new Streamed(DATA), 1).runDetailed(spec)))
                    .as(range[0] + " ~ " + range[1]).isEqualTo(ReportWriter.render(runner(1).runDetailed(spec)));
        }
    }

    @Test
    void 두_번째_읽기가_첫_번째와_다르면_결과_없이_실패한다() {
        Streamed shrinking = new Streamed(DATA) {
            @Override
            public void forEach(java.util.function.Consumer<MarketEvent> sink) {
                reads++;
                List<MarketEvent> events = reads == 1 ? EVENTS : EVENTS.subList(0, EVENTS.size() - 1);
                events.forEach(sink);
            }
        };
        assertThatThrownBy(() -> runner(shrinking, 1).runDetailed(spec(List.of(MKT_HOLD))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("두 번째 읽기");
        Streamed unordered = new Streamed(DATA) {
            @Override
            public void forEach(java.util.function.Consumer<MarketEvent> sink) {
                sink.accept(EVENTS.get(1));
                sink.accept(EVENTS.get(0));
            }
        };
        assertThatThrownBy(() -> runner(unordered, 1).runDetailed(spec(List.of(MKT_HOLD))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("오름차순");
    }

    @Test
    void 목록이_아닌_입력도_정지_확인에서_멈추면_결과_없이_끝난다() {
        // 첫 번째 읽기의 거래일 확인(2일), 선정 전 확인, Run 시작 전(1) 뒤 Run 안 거래일 바뀜에서 멈춘다
        for (int stopAt = 1; stopAt <= 6; stopAt++) {
            int limit = stopAt;
            int[] asked = {0};
            RunGate gate = () -> ++asked[0] >= limit ? java.util.Optional.of("정지 요청") : java.util.Optional.empty();
            SimulationExperimentRunner r = new SimulationExperimentRunner(new Streamed(DATA), TestKrxRules.INSTANCE, COST,
                    new FixtureStrategies(), RiskChain.builder(BlacklistSnapshot.EMPTY).build(), ExecutionSettings.defaults(),
                    new CodeVersion("test", false), 1, SAMSUNG, null, gate);
            if (limit <= 5) {
                assertThatThrownBy(() -> r.runDetailed(spec(List.of(MKT_HOLD))))
                        .as("정지 확인 " + limit + "번째").isInstanceOf(ExperimentStoppedException.class);
            } else {
                assertThat(r.runDetailed(spec(List.of(MKT_HOLD))).result().runs()).hasSize(1);
            }
        }
    }

    @Test
    void 목록이_아닌_입력은_예열을_받지_않는다() {
        SimulationExperimentRunner r = new SimulationExperimentRunner(new Streamed(DATA), TestKrxRules.INSTANCE, COST,
                new FixtureStrategies(), RiskChain.builder(BlacklistSnapshot.EMPTY).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, SAMSUNG, null, RunGate.NONE, 1);
        assertThatThrownBy(() -> r.runDetailed(spec(List.of(MKT_HOLD)))).hasMessageContaining("예열");
    }

    /**
     * 레인 경로(D-116): 체결 지연·시드·프로필이 다른 실험 여럿을 입력 한 번 읽기(두 번 읽음)에 함께 돌려도, 레인마다 그 지연으로 만든
     * 실행기의 {@code runDetailed}와 보고서가 바이트로 같다. 한 레인의 실패 계좌는 다른 레인을 바꾸지 않는다.
     */
    @Test
    void 레인_여럿을_한_번_읽기에_돌려도_레인마다_따로_돈_결과와_같다() {
        Streamed streamed = new Streamed(DATA);
        ExperimentSpec a = spec(List.of(MKT_HOLD, LIMIT_MAKER, PROBE));
        ExperimentSpec b = ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD-L2000"), FROM, TO, 8,
                Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, List.of(LIMIT_MAKER, BOOM, PROBE));
        ExperimentSpec c = ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD-L0"), FROM, TO, 9,
                Money.of(5_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, List.of(MKT_HOLD));
        ExecutionSettings slow = new ExecutionSettings(java.time.Duration.ofSeconds(2));
        ExecutionSettings none = new ExecutionSettings(java.time.Duration.ZERO);

        List<SimulationReport> lanes = runner(streamed, 1).runLanes(List.of(
                new SimulationExperimentRunner.Lane(a, ExecutionSettings.defaults()),
                new SimulationExperimentRunner.Lane(b, slow),
                new SimulationExperimentRunner.Lane(c, none)), "EXP-20261008-GOLD-JUDGE");

        assertThat(streamed.reads).isEqualTo(2);
        assertThat(lanes).hasSize(3);
        assertThat(ReportWriter.render(lanes.get(0))).isEqualTo(ReportWriter.render(runner(1).runDetailed(a)));
        assertThat(ReportWriter.render(lanes.get(1))).isEqualTo(ReportWriter.render(runner(DATA, 1, slow).runDetailed(b)));
        assertThat(ReportWriter.render(lanes.get(2))).isEqualTo(ReportWriter.render(runner(DATA, 1, none).runDetailed(c)));
        assertThat(lanes.get(1).result().executionModelId()).isEqualTo(slow.id());
        assertThat(lanes.get(1).result().runs()).extracting(rr -> rr.status())
                .containsExactly(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.COMPLETED);
        // 지연이 다르면 실제로 다른 결과다(레인 설정이 쓰인다)
        assertThat(ReportWriter.render(runner(DATA, 1, slow).runDetailed(a))).isNotEqualTo(ReportWriter.render(lanes.get(0)));
    }

    @Test
    void 레인_기간이_다르거나_입력과_맞지_않으면_아무_Run도_시작하지_않는다() {
        Streamed streamed = new Streamed(DATA);
        ExperimentSpec a = spec(List.of(MKT_HOLD));
        ExperimentSpec shorter = ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD-S"), FROM, FROM, 7,
                Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, List.of(MKT_HOLD));
        ExperimentSpec otherData = ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD-D"), FROM, TO, 7,
                Money.of(10_000_000, Money.KRW), COST.id(), "OTHER", ClockMode.REPLAY, List.of(MKT_HOLD));
        SimulationExperimentRunner r = runner(streamed, 1);
        assertThatThrownBy(() -> r.runLanes(List.of(new SimulationExperimentRunner.Lane(a, ExecutionSettings.defaults()),
                new SimulationExperimentRunner.Lane(shorter, ExecutionSettings.defaults())), "x"))
                .hasMessageContaining("레인 기간이 다릅니다");
        assertThatThrownBy(() -> r.runLanes(List.of(new SimulationExperimentRunner.Lane(a, ExecutionSettings.defaults()),
                new SimulationExperimentRunner.Lane(otherData, ExecutionSettings.defaults())), "x"))
                .hasMessageContaining("데이터 스냅샷");
        assertThatThrownBy(() -> r.runLanes(List.of(), "x")).hasMessageContaining("레인이 없습니다");
        assertThat(streamed.reads).isZero();
    }

    @Test
    void 레인_경로의_진행_보고는_모든_레인의_Run을_합쳐_낸다() {
        List<ExperimentProgress> seen = new java.util.ArrayList<>();
        RunGate gate = new RunGate() {
            @Override
            public java.util.Optional<String> stopReason() {
                return java.util.Optional.empty();
            }

            @Override
            public void onProgress(ExperimentProgress p) {
                seen.add(p);
            }
        };
        SimulationExperimentRunner r = new SimulationExperimentRunner(new Streamed(DATA), TestKrxRules.INSTANCE, COST,
                new FixtureStrategies(), RiskChain.builder(BlacklistSnapshot.EMPTY).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, SAMSUNG, null, gate);
        ExperimentSpec b = ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD-B"), FROM, TO, 8,
                Money.of(10_000_000, Money.KRW), COST.id(), DATA.version(), ClockMode.REPLAY, List.of(LIMIT_MAKER, PROBE));
        r.runLanes(List.of(new SimulationExperimentRunner.Lane(spec(List.of(MKT_HOLD)), ExecutionSettings.defaults()),
                new SimulationExperimentRunner.Lane(b, ExecutionSettings.defaults())), "EXP-JUDGE");
        ExperimentProgress last = seen.get(seen.size() - 1);
        assertThat(last.experimentId()).isEqualTo("EXP-JUDGE");
        assertThat(last.phase()).isEqualTo(ExperimentProgress.Phase.FINISHED);
        assertThat(last.runsTotal()).isEqualTo(3);
        assertThat(last.runsFinished()).isEqualTo(3);
        assertThat(last.daysDone()).isEqualTo(last.daysTotal());
    }

    /** 목록 입력을 감싸 {@link MarketDataSet}이 아닌 입력으로 만든다(실행기가 목록 경로를 못 타게). 읽은 횟수를 센다. */
    private static class Streamed implements MarketEventStream {
        private final MarketDataSet inner;
        int reads;

        Streamed(MarketDataSet inner) {
            this.inner = inner;
        }

        @Override
        public String version() {
            return inner.version();
        }

        @Override
        public Market market() {
            return inner.market();
        }

        @Override
        public long eventCount() {
            return inner.events().size();
        }

        @Override
        public void forEach(java.util.function.Consumer<MarketEvent> sink) {
            reads++;
            inner.events().forEach(sink);
        }
    }

    // ---- 도우미 ----

    private static SimulationExperimentRunner runner(int parallelism) {
        return runner(DATA, parallelism);
    }

    private static SimulationExperimentRunner runner(MarketEventStream data, int parallelism) {
        return runner(data, parallelism, ExecutionSettings.defaults());
    }

    private static SimulationExperimentRunner runner(MarketEventStream data, int parallelism, ExecutionSettings execution) {
        return new SimulationExperimentRunner(data, TestKrxRules.INSTANCE, COST, new FixtureStrategies(),
                RiskChain.builder(BlacklistSnapshot.of(List.of(new BlacklistEntry(HYNIX, BlacklistType.MANUAL, "테스트"))))
                        .build(),
                execution, new CodeVersion("test", false),
                parallelism, SAMSUNG);
    }

    private static ExperimentSpec spec(List<StrategyProfile> profiles) {
        return spec(DATA, profiles);
    }

    private static ExperimentSpec spec(MarketEventStream data, List<StrategyProfile> profiles) {
        return ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261008-GOLD"), FROM, TO, 7, Money.of(10_000_000, Money.KRW),
                COST.id(), data.version(), ClockMode.REPLAY, profiles);
    }

    private static StrategyProfile profile(String id, String... kv) {
        TreeMap<String, String> params = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return new StrategyProfile(id, 1, Market.KRX, TradingStyle.INTRADAY, params);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = SimulationExperimentRunnerTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("골든 파일 " + resource + " 없음. -Dgolden.update=true로 만든다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
