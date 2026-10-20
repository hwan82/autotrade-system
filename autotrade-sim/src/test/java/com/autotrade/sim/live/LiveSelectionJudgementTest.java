package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.recording.JournalWriter;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.SelectionFixtures;
import com.autotrade.sim.selection.SelectionResultFile;
import com.autotrade.sim.selection.SessionSelections;
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
 * 녹화 판정의 선정 조합 계좌(D-124 후속): 모든 레인이 세션이 남긴 같은 적용 기록·결과 파일을 읽어, 변형·지연·대조군·녹화 계좌도
 * 실시간 계좌와 같은 날 같은 후보로만 새로 산다. 대조군 분포는 선정 묶음마다 따로 돌린다.
 */
class LiveSelectionJudgementTest {

    private static final Path REPO = Path.of("..");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String ID = "EXP-TEST-LIVESELJ";
    private static final String SRC = "KIWOOM-WS";
    private static final LocalDate D1 = LocalDate.of(2026, 10, 14);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 15);
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final SelectionResultFile.Producer PRODUCER = new SelectionResultFile.Producer("prep", false, null);

    @TempDir
    Path tmp;

    Path experimentDir;
    Path sessions;
    Path recordings;
    LivePlan plan;
    SelectionProfile hot;
    final LiveSessionTest.Mutable clock = new LiveSessionTest.Mutable();

    @BeforeEach
    void register() throws Exception {
        hot = SelectionFixtures.intradayHot(REPO);
        experimentDir = tmp.resolve("experiments").resolve(ID);
        Files.createDirectories(experimentDir);
        sessions = tmp.resolve("live-sessions");
        recordings = tmp.resolve("recordings");
        writePlan(List.of("kr-intraday-hot@1 x kr-orb-30@1", "kr-intraday-hot@1 x kr-random-control@1"));
    }

    private void writePlan(List<String> selections) throws Exception {
        StringBuilder yaml = new StringBuilder(String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"선정 조합 계좌가 판정 기준을 넘는가\"",
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
                "  - \"kr-orb-30@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "  - \"000660 KOSPI\"",
                "selections:", ""));
        selections.forEach(s -> yaml.append("  - \"").append(s).append("\"\n"));
        yaml.append("selection-input:\n  daily: \"latest\"\n");
        Files.writeString(experimentDir.resolve("experiment.yaml"), yaml, StandardCharsets.UTF_8);
        plan = LivePlan.parse(yaml.toString(), "experiment.yaml");
    }

    /** D1 결과(005930·boards 밖 035720)만 놓고 세션을 끝까지 돌린다. D2는 결과가 없어 SELECTION_MISSING. 녹화도 같은 이벤트. */
    private SimulationReport operate() throws Exception {
        LiveSetup setup = LiveSetup.of(plan, REPO);
        setup.selectionFiles(sessions.resolve(ID)).writeResult(SelectionFixtures.result(hot, D1, List.of("005930", "035720")),
                PRODUCER);
        List<MarketEvent> events = LiveSessionTest.twoDays();
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
        return live;
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
    void selectionAccountsAreJudgedWithTheSessionsFixedSelectionsAndTheirOwnControlDistribution() throws Exception {
        operate();

        LiveJudgement.Outcome out = judge(4);

        assertThat(out.incident()).isFalse();
        assertThat(out.verdicts()).containsOnlyKeys(ID + "-A01", ID + "-A02", ID + "-A03", ID + "-A04", ID + "-A05");
        String r = out.report();
        assertThat(r).contains("- I3 실시간 = 입력 기록 리플레이: 같음")
                .contains("판정 대상 m = 2, 대조군 시드 N = 41")
                .contains("- 선정 조합 계좌(D-124): 모든 레인이 세션이 남긴 같은 선정 적용 기록")
                // 레인: BASE 5 + PERTURB 4 + 지연 3 × 2 + 대조군 41 × 선정 묶음 2(선정 없음, kr-intraday-hot) = Run 97
                .contains("입력 기록 레인 87개(Run 97개), ")
                .contains("| " + ID + "-A04 | kr-intraday-hot@1 x kr-orb-30@1 | 판정 대상 | **")
                .contains("| " + ID + "-A05 | kr-intraday-hot@1 x kr-random-control@1 | 대조군 | (참고) ")
                .contains("| " + ID + "-A02 | kr-random-control@1 | 대조군 | (참고) ")
                .contains("### 선정 없음(boards 전체) — 대조군 " + ID + "-A02 kr-random-control@1, 시드 레인 " + ID + "-NULL1 ~")
                .contains("### 선정 kr-intraday-hot@1 — 대조군 " + ID + "-A05 kr-intraday-hot@1 x kr-random-control@1, 시드 레인 "
                        + ID + "-S1-NULL1 ~")
                .contains("- kr-intraday-hot@1 x kr-orb-30: signal.range-minutes ×0.8 = 24, ×1.2 = 36")
                .contains("## 9. 선정(D-124)")
                .contains("| " + D1 + " | kr-intraday-hot@1 | APPLIED | 005930 | 035720 | - | 1/2 |")
                .contains("| " + D2 + " | kr-intraday-hot@1 | SELECTION_MISSING(MISSING) |");
        // L10 줄의 N·k는 그 계좌 선정 묶음 분포로 계산했다(strategy-researcher PR #199 R5)
        assertL10UsesGroupDistribution(r, ID + "-A04", ID + "-S1-NULL1");
        assertL10UsesGroupDistribution(r, ID + "-A03", ID + "-NULL1");
        // 같은 이벤트를 녹화했으므로 선정 계좌도 녹화 리플레이가 실시간과 같다(REC 레인도 같은 적용 기록을 읽음)
        assertThat(r.substring(r.indexOf("### " + ID + "-A04 "), r.indexOf("### " + ID + "-A05 ")))
                .contains("- L11 실시간 대 녹화: 수익률 차 ≤ 2.00%p, 체결 수 차 ≤ 10.00%: 통과");
        // 묶음 수는 결과에 영향이 없다
        assertThat(judge(1).report()).isEqualTo(r);
    }

    @Test
    void everyLaneOfASelectionAccountBuysOnlyThatDaysCandidates() throws Exception {
        operate();
        LiveSetup setup = LiveSetup.of(plan, REPO);
        Path sessionDir = sessions.resolve(ID);
        StreamedRecordingDataSet journal = StreamedRecordingDataSet.open(Market.KRX,
                LiveReplayCheck.journalDays(setup, sessionDir), () -> { }, e -> { });
        LiveJudgement.Roles roles = LiveJudgement.roles(setup);
        assertThat(roles.controls()).containsExactly(1, 4);
        assertThat(roles.judged()).containsExactly(2, 3);
        assertThat(roles.nullControls()).containsExactly(1, 4);
        assertThat(roles.controlOf()).containsEntry(2, 1).containsEntry(3, 4).containsEntry(0, 1);
        LiveJudgement.Plan lanes = LiveJudgement.lanes(setup, roles, journal.version(), 3, m -> { });
        assertThat(lanes.nullsByControl().get(4)).extracting(l -> l.spec().id().value())
                .containsExactly(ID + "-S1-NULL1", ID + "-S1-NULL2", ID + "-S1-NULL3");
        assertThat(lanes.nullsByControl().get(4).get(0).spec().randomSeed()).isEqualTo(plan.seed() + 1);

        List<SimulationReport> reports = new SimulationExperimentRunner(journal, setup.rules(), setup.cost(), setup.factory(),
                setup.risk(), setup.execution(), new CodeVersion("c", false), 1, setup.benchmark(),
                LiveReplayCheck.selectionSource(setup, sessionDir)).runLanes(lanes.all(), "x");
        int selectionRuns = 0;
        long selectionBuys = 0;
        for (SimulationReport report : reports) {
            for (RunReport run : report.runs()) {
                if (!run.combination().hasSelection()) {
                    continue;
                }
                selectionRuns++;
                selectionBuys += run.fills().stream().filter(f -> f.fill().side() == Side.BUY).count();
                assertThat(run.fills()).filteredOn(f -> f.fill().side() == Side.BUY).allSatisfy(f -> {
                    assertThat(f.fill().symbol()).isEqualTo(SAMSUNG);
                    assertThat(f.fill().exchangeDate()).isEqualTo(D1);
                });
            }
        }
        // BASE 2 + PERTURB 2 + 지연 3 + 선정 대조군 3
        assertThat(selectionRuns).isEqualTo(10);
        assertThat(selectionBuys).isPositive();
    }

    @Test
    void selectionJudgedAccountNeedsAControlWithTheSameSelection() throws Exception {
        writePlan(List.of("kr-intraday-hot@1 x kr-orb-30@1"));
        assertThatThrownBy(() -> LiveJudgement.roles(LiveSetup.of(plan, REPO))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 선정의 대조군(logic: random)");
        // 판정 대상이 없는 선정 묶음의 대조군은 참고로만 판정하고 시드 분포를 돌리지 않는다
        writePlan(List.of("kr-intraday-hot@1 x kr-random-control@1"));
        LiveJudgement.Roles roles = LiveJudgement.roles(LiveSetup.of(plan, REPO));
        assertThat(roles.controls()).containsExactly(1, 3);
        assertThat(roles.nullControls()).containsExactly(1);
        assertThat(LiveJudgement.lanes(LiveSetup.of(plan, REPO), roles, "v", 3, m -> { }).nullsByControl())
                .containsOnlyKeys(1);
    }

    @Test
    void doesNotJudgeWhenTheAppliedRecordOfARecordedDayIsMissingOrTheResultFileChanged() throws Exception {
        operate();
        SessionSelections files = LiveSetup.of(plan, REPO).selectionFiles(sessions.resolve(ID));
        Path result = files.resultFile(D1, hot.selectionHash());
        String original = Files.readString(result);
        Files.writeString(result, SelectionResultFile.write(SelectionFixtures.result(hot, D1, List.of("000660")), PRODUCER),
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> judge(2)).isInstanceOf(IllegalStateException.class).hasMessageContaining("내용 해시");
        Files.writeString(result, original, StandardCharsets.UTF_8);
        Files.delete(files.appliedFile(D1));
        assertThatThrownBy(() -> judge(2)).isInstanceOf(IllegalStateException.class).hasMessageContaining("적용 기록")
                .hasMessageContaining(D1.toString());
    }

    /**
     * 계좌의 L10 줄(3장)이 쓴 대조군 수 N이 그 계좌 선정 묶음의 분포 요약(6장, 시드 레인 {@code firstNullLane}으로 시작하는 절)의
     * "왕복당 순손익을 쓴 대조군 N개"와 같은지 본다(strategy-researcher PR #199 R5). 묶음 연결이 바뀌면 다른 묶음의 N이 들어가 깨진다.
     */
    static void assertL10UsesGroupDistribution(String report, String account, String firstNullLane) {
        String six = report.substring(report.indexOf("## 6. 무작위 대조군 분포(L10)"), report.indexOf("## 7. "));
        int header = six.indexOf("시드 레인 " + firstNullLane + " ~");
        String group = header < 0 ? six : six.substring(header);
        java.util.regex.Matcher n = java.util.regex.Pattern.compile("왕복당 순손익을 쓴 대조군 (\\d+)개").matcher(group);
        assertThat(n.find()).as("분포 요약 " + firstNullLane).isTrue();
        String three = report.substring(report.indexOf("## 3. 항목별 근거"), report.indexOf("## 4. "));
        String section = three.substring(three.indexOf("### " + account + " "));
        int next = section.indexOf("\n### ", 1);
        String l10 = (next < 0 ? section : section.substring(0, next)).lines().filter(l -> l.startsWith("- L10 "))
                .findFirst().orElseThrow();
        assertThat(l10).as(account).containsAnyOf("/" + n.group(1) + ", p=", "N=" + n.group(1) + ",");
    }
}
