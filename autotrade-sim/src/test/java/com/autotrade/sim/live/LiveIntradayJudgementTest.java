package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.recording.JournalWriter;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.IntradayTracker;
import com.autotrade.sim.selection.SelectionFixtures;
import com.autotrade.sim.selection.SelectionResultFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 녹화 판정의 장중 순위 선정 계좌(D-128, D-127 후속). 장전 선정(D-124)과 장중 순위 선정이 한 등록에 함께 있는 경우(architect PR #200
 * R3)로 본다: 장전 선정 계좌는 세션 고정 파일을, 장중 순위 계좌는 레인마다 따로 돈 추적기를 쓴다. 변형·지연·대조군 레인의 장중
 * 계좌도 실시간 계좌와 같은 날 같은 후보를 같은 순번부터 산다. 대조군 분포는 선정 묶음마다 따로 돌린다.
 */
class LiveIntradayJudgementTest {

    private static final Path REPO = Path.of("..");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String ID = "EXP-TEST-LIVEATJ";
    private static final String SRC = "KIWOOM-WS";
    private static final LocalDate D1 = LocalDate.of(2026, 10, 14);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 15);
    private static final SelectionResultFile.Producer PRODUCER = new SelectionResultFile.Producer("prep", false, null);

    @TempDir
    Path tmp;

    Path experimentDir;
    Path sessions;
    Path recordings;
    LivePlan plan;
    final LiveSessionTest.Mutable clock = new LiveSessionTest.Mutable();

    @BeforeEach
    void register() throws Exception {
        experimentDir = tmp.resolve("experiments").resolve(ID);
        Files.createDirectories(experimentDir);
        sessions = tmp.resolve("live-sessions");
        recordings = tmp.resolve("recordings");
        String yaml = String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"장전·장중 선정 계좌가 판정 기준을 넘는가\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-15\"",
                "seed: \"20261014\"",
                "seed-capital: \"10000000\"",
                "benchmark: \"005930\"",
                "profiles:",
                "  - \"kr-bh-baseline@1\"",
                "  - \"kr-random-control@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "  - \"000660 KOSPI\"",
                "selections:",
                "  - \"kr-intraday-hot@1 x kr-orb-30@1\"",
                "  - \"kr-intraday-hot@1 x kr-random-control@1\"",
                "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\"",
                "  - \"kr-intraday-open-momentum@1 x kr-random-control@1\"",
                "selection-input:",
                "  daily: \"latest\"",
                "");
        Files.writeString(experimentDir.resolve("experiment.yaml"), yaml, StandardCharsets.UTF_8);
        plan = LivePlan.parse(yaml, "experiment.yaml");
    }

    /** 장전 선정은 D1 결과(005930)만 놓는다(D2는 SELECTION_MISSING). 장중 순위는 세션이 매긴다. 녹화도 같은 이벤트. */
    private void operate() throws Exception {
        LiveSetup setup = LiveSetup.of(plan, REPO);
        SelectionProfile hot = SelectionFixtures.intradayHot(REPO);
        setup.selectionFiles(sessions.resolve(ID)).writeResult(SelectionFixtures.result(hot, D1, List.of("005930")), PRODUCER);
        List<MarketEvent> events = LiveIntradaySessionTest.events();
        LiveSession s = LiveSession.open(plan, REPO, sessions, clock, m -> { });
        for (MarketEvent e : events) {
            clock.now = e.receivedAt();
            s.accept(SRC, e);
        }
        SimulationReport live = s.finish(new CodeVersion("live-commit", false));
        s.close();
        Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), ReportWriter.render(live), StandardCharsets.UTF_8);
        JournalWriter w = new JournalWriter(new RecordingLayout(recordings), Market.KRX, SEOUL, Map.of(SRC, "테스트 녹화"), "테스트");
        for (MarketEvent e : events) {
            w.append(SRC, e, e.receivedAt());
        }
        w.close(events.get(events.size() - 1).receivedAt());
    }

    private LiveJudgement.Outcome judge(int parallel) {
        RunGate gate = new RunGate() {
            @Override
            public Optional<String> stopReason() {
                return Optional.empty();
            }

            @Override
            public int maxParallelRuns() {
                return parallel;
            }
        };
        return LiveJudgement.run(experimentDir, REPO, sessions, recordings, new CodeVersion("judge-commit", false), m -> { },
                gate);
    }

    @Test
    void intradayAccountsAreJudgedWithTheirOwnControlDistributionNextToPreOpenSelections() throws Exception {
        operate();

        LiveJudgement.Outcome out = judge(4);

        assertThat(out.incident()).isFalse();
        assertThat(out.verdicts()).containsOnlyKeys(ID + "-A01", ID + "-A02", ID + "-A03", ID + "-A04", ID + "-A05",
                ID + "-A06");
        String r = out.report();
        String momentum = "kr-intraday-open-momentum@1";
        assertThat(r).contains("- I3 실시간 = 입력 기록 리플레이: 같음")
                .contains("판정 대상 m = 2, 대조군 시드 N = 41")
                .contains("- 선정 조합 계좌(D-124): ")
                .contains("- 장중 순위 선정 계좌(D-127, D-128): ")
                // 레인: BASE 6 + PERTURB 4 + 지연 3 × 2 + 대조군 41 × 선정 묶음 2(kr-intraday-hot, momentum) = Run 98, 레인 1 + 1 + 3 + 82
                .contains("입력 기록 레인 87개(Run 98개), ")
                .contains("| " + ID + "-A05 | " + momentum + " x kr-orb-30@1 | 판정 대상 | **")
                .contains("| " + ID + "-A06 | " + momentum + " x kr-random-control@1 | 대조군 | (참고) ")
                .contains("### 선정 kr-intraday-hot@1 — 대조군 " + ID + "-A04 kr-intraday-hot@1 x kr-random-control@1, 시드 레인 "
                        + ID + "-S1-NULL1 ~")
                .contains("### 선정 " + momentum + " — 대조군 " + ID + "-A06 " + momentum + " x kr-random-control@1, 시드 레인 "
                        + ID + "-S2-NULL1 ~")
                .contains("## 9. 선정(D-124)")
                .contains("## 10. 장중 순위 선정(D-127)");
        // 장중 순위 표: 입력 기록이 있는 날마다 순위·적용 시작 순번, 녹화(L11)도 같은 이벤트라 같은 후보
        LiveSetup setup = LiveSetup.of(plan, REPO);
        StreamedRecordingDataSet journal = StreamedRecordingDataSet.open(Market.KRX,
                LiveReplayCheck.journalDays(setup, sessions.resolve(ID)), () -> { }, e -> { });
        List<IntradayTracker.Ranked> ranks = LiveJudgement.intradayRanks(setup, journal);
        assertThat(ranks).extracting(x -> x.result().date()).containsExactly(D1, D2);
        String table = r.substring(r.indexOf("## 10. 장중 순위 선정(D-127)"));
        for (IntradayTracker.Ranked x : ranks) {
            assertThat(x.candidates().candidates()).isNotEmpty();
            assertThat(table).contains("| " + x.result().date() + " | " + momentum + " | 09:30 | " + x.candidates().selectedCount()
                    + " | " + String.join(", ", x.candidates().candidates()) + " | - | " + x.activeFrom() + " | ");
        }
        assertThat(table.lines().filter(l -> l.startsWith("| 2026-")))
                .hasSize(2).allSatisfy(l -> assertThat(l).endsWith(" | 같음 |"));
        // L10 줄의 N은 그 계좌 선정 묶음 분포의 N이다(PR #199 strategy-researcher R5)
        LiveSelectionJudgementTest.assertL10UsesGroupDistribution(r, ID + "-A03", ID + "-S1-NULL1");
        LiveSelectionJudgementTest.assertL10UsesGroupDistribution(r, ID + "-A05", ID + "-S2-NULL1");
        // 같은 이벤트를 녹화했으므로 장중 계좌도 녹화 리플레이가 실시간과 같다
        assertThat(r.substring(r.indexOf("### " + ID + "-A05 "), r.indexOf("### " + ID + "-A06 ")))
                .contains("- L11 실시간 대 녹화: 수익률 차 ≤ 2.00%p, 체결 수 차 ≤ 10.00%: 통과");
        // 묶음 수는 결과에 영향이 없다
        assertThat(judge(1).report()).isEqualTo(r);
    }

    @Test
    void everyLaneOfAnIntradayAccountBuysOnlyThatDaysCandidatesFromTheRankEvent() throws Exception {
        operate();
        LiveSetup setup = LiveSetup.of(plan, REPO);
        Path sessionDir = sessions.resolve(ID);
        StreamedRecordingDataSet journal = StreamedRecordingDataSet.open(Market.KRX,
                LiveReplayCheck.journalDays(setup, sessionDir), () -> { }, e -> { });
        LiveJudgement.Roles roles = LiveJudgement.roles(setup);
        assertThat(roles.judged()).containsExactly(2, 4);
        assertThat(roles.nullControls()).containsExactly(3, 5);
        LiveJudgement.Plan lanes = LiveJudgement.lanes(setup, roles, journal.version(), 3, m -> { });
        Map<String, IntradayTracker.Ranked> ranks = new java.util.HashMap<>();
        LiveJudgement.intradayRanks(setup, journal).forEach(x -> ranks.put(x.result().date().toString(), x));
        assertThat(ranks).hasSize(2);

        List<SimulationReport> reports = new SimulationExperimentRunner(journal, setup.rules(), setup.cost(), setup.factory(),
                setup.risk(), setup.execution(), new CodeVersion("c", false), 1, setup.benchmark(),
                LiveReplayCheck.selectionSource(setup, sessionDir)).withIntradayUniverse(setup.intradayUniverse())
                .runLanes(lanes.all(), "x");
        int intradayRuns = 0;
        long buys = 0;
        for (SimulationReport report : reports) {
            for (RunReport run : report.runs()) {
                if (!run.combination().selectionKeyOrNone().startsWith("kr-intraday-open-momentum")) {
                    continue;
                }
                intradayRuns++;
                // 모든 레인이 같은 날 같은 순위·같은 적용 시작 순번
                assertThat(run.selectionDays()).hasSize(2).allSatisfy(d -> {
                    IntradayTracker.Ranked x = ranks.get(d.selection().date().toString());
                    assertThat(d.selection().resultHash()).isEqualTo(x.contentHash());
                    assertThat(d.selection().activeFrom()).isEqualTo(x.activeFrom());
                });
                List<RunSelectionDay> days = run.selectionDays();
                buys += run.fills().stream().filter(f -> f.fill().side() == Side.BUY).count();
                assertThat(run.fills()).filteredOn(f -> f.fill().side() == Side.BUY).allSatisfy(f -> {
                    RunSelectionDay d = days.stream().filter(x -> x.selection().date().equals(f.fill().exchangeDate()))
                            .findFirst().orElseThrow();
                    assertThat(d.selection().symbols()).contains(f.fill().symbol());
                    assertThat(f.fill().signalEventSeq()).isGreaterThanOrEqualTo(d.selection().activeFrom());
                });
            }
        }
        // BASE 2 + PERTURB 2 + 지연 3 + 장중 대조군 3
        assertThat(intradayRuns).isEqualTo(10);
        assertThat(buys).isPositive();
        // 레인을 하나씩 따로 돌려도 같은 결과다(레인끼리 추적기를 나눠 쓰지 않음, architect PR #200 R6)
        SimulationExperimentRunner alone = new SimulationExperimentRunner(journal, setup.rules(), setup.cost(),
                setup.factory(), setup.risk(), setup.execution(), new CodeVersion("c", false), 1, setup.benchmark(),
                LiveReplayCheck.selectionSource(setup, sessionDir)).withIntradayUniverse(setup.intradayUniverse());
        for (int i : List.of(0, 1, lanes.all().size() - 1)) {
            SimulationReport one = alone.runLanes(List.of(lanes.all().get(i)), "y").get(0);
            assertThat(ReportWriter.render(one)).isEqualTo(ReportWriter.render(reports.get(i)));
        }
    }
}
