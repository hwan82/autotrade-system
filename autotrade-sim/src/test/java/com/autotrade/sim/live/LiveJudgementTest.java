package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.marketdata.recording.JournalWriter;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 6단계 녹화 판정(D-116): 끝난 세션의 입력 기록으로 L1~L11을 계산한다. 원래 성과는 실시간 결과와 같고(I3), 변형·지연 레인은 따로
 * 돈 실험과 같으며, 묶음 수를 바꿔도 결과 파일이 바이트로 같다.
 */
class LiveJudgementTest {

    private static final Path REPO = Path.of("..");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String ID = "EXP-TEST-LIVE01";
    private static final String SRC = "KIWOOM-WS";

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
        writePlan(List.of("kr-bh-baseline@1", "kr-random-control@1", "kr-orb-30@1", "kr-vwap-reclaim@1"));
        sessions = tmp.resolve("live-sessions");
        recordings = tmp.resolve("recordings");
    }

    private void writePlan(List<String> profiles) throws Exception {
        StringBuilder yaml = new StringBuilder(String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"장중 프로필이 판정 기준을 넘는가\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-15\"",
                "seed: \"20261014\"",
                "seed-capital: \"10000000\"",
                "benchmark: \"005930\"",
                "profiles:", ""));
        profiles.forEach(p -> yaml.append("  - \"").append(p).append("\"\n"));
        yaml.append("boards:\n  - \"005930 KOSPI\"\n  - \"000660 KOSPI\"\n");
        Files.writeString(experimentDir.resolve("experiment.yaml"), yaml, StandardCharsets.UTF_8);
        plan = LivePlan.parse(yaml.toString(), "experiment.yaml");
    }

    /** 실험실 세션을 끝까지 돌리고 live-detail.txt를 남긴다. 게이트웨이 녹화도 같은 이벤트로 쓴다. */
    private SimulationReport operate(boolean record) throws Exception {
        List<MarketEvent> events = LiveSessionTest.twoDays();
        LiveSession s = LiveSession.open(plan, REPO, sessions, clock, m -> { });
        for (MarketEvent e : events) {
            clock.now = e.receivedAt();
            s.accept(SRC, e);
        }
        SimulationReport live = s.finish(new CodeVersion("live-commit", false));
        s.close();
        Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), ReportWriter.render(live), StandardCharsets.UTF_8);
        if (record) {
            JournalWriter w = new JournalWriter(new RecordingLayout(recordings), Market.KRX, SEOUL, Map.of(SRC, "테스트 녹화"),
                    "테스트");
            for (MarketEvent e : events) {
                w.append(SRC, e, e.receivedAt());
            }
            w.close(events.get(events.size() - 1).receivedAt());
        }
        return live;
    }

    private LiveJudgement.Outcome judge(RunGate gate) {
        return LiveJudgement.run(experimentDir, REPO, sessions, recordings, new CodeVersion("judge-commit", false), m -> { },
                gate);
    }

    private static RunGate parallel(int n) {
        return new RunGate() {
            @Override
            public Optional<String> stopReason() {
                return Optional.empty();
            }

            @Override
            public int maxParallelRuns() {
                return n;
            }
        };
    }

    @Test
    void judgesEveryAccountFromTheJournalAndTheReportDoesNotDependOnGrouping() throws Exception {
        operate(true);

        LiveJudgement.Outcome four = judge(parallel(4));

        assertThat(four.incident()).isFalse();
        assertThat(four.verdicts()).containsOnlyKeys(ID + "-A01", ID + "-A02", ID + "-A03", ID + "-A04");
        // 이틀 운용이라 샤프(20거래일 미만)·L6(뒤 4주 없음)은 판정 불가 → 후보가 될 수 없다
        assertThat(four.verdicts().values()).doesNotContain("후보");
        String r = four.report();
        assertThat(r).contains("- I3 실시간 = 입력 기록 리플레이: 같음")
                .contains("- I1 실패한 계좌 0개, I2 블랙리스트 종목 체결 0건")
                .contains("판정 대상 m = 2, 대조군 시드 N = 41 (실험 시드 + 1 ~ + 41, 판정 가능 최소 39에 여유 2)")
                // 레인: BASE 4 + PERTURB 4 + 지연 3 × 2 + 대조군 41(21m − 1) = Run 55
                .contains("입력 기록 레인 46개(Run 55개), ")
                .contains("다중 비교 보정(본페로니 0.05/m)은 L10에만 있다")
                .contains("| " + ID + "-A03 | kr-orb-30@1 | 판정 대상 | **")
                .contains("| " + ID + "-A01 | kr-bh-baseline@1 | 기준선 | (참고) ")
                .contains("- L4 샤프 ≥ 1.0: 판정 불가")
                .contains("- L6 앞 4주·뒤 4주 각각 순손익 > 0: 판정 불가")
                .contains("signal.range-minutes ×0.8 = 24, ×1.2 = 36")
                .contains("signal.dip-pct ×0.8 = 0.64, ×1.2 = 0.96")
                .contains("## 7. 실시간 대 녹화(L11)");
        // 같은 이벤트를 녹화했으므로 녹화 리플레이가 실시간과 같다(L11 통과, 진입 일치율 100% 또는 진입 없음)
        assertThat(r).contains("- L11 실시간 대 녹화: 수익률 차 ≤ 2.00%p, 체결 수 차 ≤ 10.00%: 통과");
        assertThat(r).doesNotContain("- L11 실시간 대 녹화: 수익률 차 ≤ 2.00%p, 체결 수 차 ≤ 10.00%: 탈락");

        // 묶음 수(스레드 수)는 결과에 영향이 없다(보고서에 묶음 수를 쓰지 않아 바이트로 같음, PR #180 architect N3)
        assertThat(judge(parallel(1)).report()).isEqualTo(r);
        assertThat(judge(parallel(2)).report()).isEqualTo(r);
    }

    @Test
    void stressLatencyColumnEqualsASeparateRunWithThatLatency() throws Exception {
        operate(true);
        LiveSetup setup = LiveSetup.of(plan, REPO);
        List<Path> days = LiveReplayCheck.journalDays(setup, sessions.resolve(ID));
        StreamedRecordingDataSet journal = StreamedRecordingDataSet.open(Market.KRX, days, () -> { }, e -> { });
        LiveJudgement.Plan lanes = LiveJudgement.lanes(setup, LiveJudgement.roles(setup), journal.version(), 39, m -> { });
        SimulationExperimentRunner.Lane twoSeconds = lanes.latency().get(2);
        assertThat(twoSeconds.execution().orderLatency()).isEqualTo(Duration.ofSeconds(2));
        SimulationReport alone = new SimulationExperimentRunner(journal, setup.rules(), setup.cost(), setup.factory(),
                setup.risk(), new ExecutionSettings(Duration.ofSeconds(2)), new CodeVersion("c", false), 1, setup.benchmark(),
                null).runDetailed(twoSeconds.spec());
        List<SimulationReport> together = new SimulationExperimentRunner(journal, setup.rules(), setup.cost(),
                setup.factory(), setup.risk(), setup.execution(), new CodeVersion("c", false), 1, setup.benchmark(), null)
                .runLanes(lanes.all(), "x");
        assertThat(ReportWriter.render(together.get(lanes.all().indexOf(twoSeconds)))).isEqualTo(ReportWriter.render(alone));
        // 기본 레인은 리플레이 확인과 같은 실험이다(실시간 세션 결과)
        assertThat(lanes.base().spec()).isEqualTo(setup.spec(ClockMode.REPLAY, journal.version()));
        // 대조군 시드는 실험 시드 + i
        assertThat(lanes.nulls()).hasSize(39);
        assertThat(lanes.nulls().get(0).spec().randomSeed()).isEqualTo(plan.seed() + 1);
        assertThat(lanes.nulls().get(38).spec().randomSeed()).isEqualTo(plan.seed() + 39);
        assertThat(lanes.nulls().get(0).spec().combinations()).singleElement()
                .satisfies(c -> assertThat(c.profile().id()).isEqualTo("kr-random-control"));
    }

    @Test
    void groupsSpreadRunsAndKeepLaneOrderInsideEachGroup() throws Exception {
        operate(false);
        LiveSetup setup = LiveSetup.of(plan, REPO);
        LiveJudgement.Plan lanes = LiveJudgement.lanes(setup, LiveJudgement.roles(setup), "v", 39, m -> { });
        List<List<SimulationExperimentRunner.Lane>> groups = LiveJudgement.groups(lanes.all(), 4);
        assertThat(groups).hasSize(4);
        assertThat(groups.stream().mapToInt(List::size).sum()).isEqualTo(lanes.all().size());
        int[] runs = groups.stream().mapToInt(g -> g.stream().mapToInt(l -> l.spec().combinations().size()).sum()).toArray();
        assertThat(java.util.Arrays.stream(runs).max().getAsInt() - java.util.Arrays.stream(runs).min().getAsInt())
                .isLessThanOrEqualTo(4);
        assertThat(LiveJudgement.groups(lanes.all(), 100)).hasSize(lanes.all().size());
    }

    @Test
    void controlSeedsLeaveAMarginOverTheMinimum() {
        // 판정 가능한 최소 20m − 1에 판정 대상 수만큼 여유(PR #180 strategy-researcher R1): m = 6이면 125
        assertThat(LiveJudgement.nullSeeds(6)).isEqualTo(125);
        assertThat(LiveJudgement.nullSeeds(2)).isEqualTo(41);
        for (int m = 1; m <= 8; m++) {
            assertThat(LiveJudgement.nullSeeds(m) + 1 - m).isEqualTo(20 * m);
        }
    }

    @Test
    void l11ReplaysOnlyJournalDays() throws Exception {
        // 운용 기간을 하루 앞당겨 10-13을 기간 안에 둔다(입력 기록은 10-14·10-15)
        Path yaml = experimentDir.resolve("experiment.yaml");
        String moved = Files.readString(yaml).replace("from: \"2026-10-14\"", "from: \"2026-10-13\"");
        Files.writeString(yaml, moved, StandardCharsets.UTF_8);
        plan = LivePlan.parse(moved, "experiment.yaml");
        operate(true);
        // 실험실은 꺼져 있고 게이트웨이만 켜진 날(운용 기간 안, 입력 기록 없음)의 녹화는 L11에 넣지 않는다(PR #180 strategy-researcher M1)
        Path day = recordings.resolve("KRX").resolve("2026-10-15");
        Path extra = recordings.resolve("KRX").resolve("2026-10-13");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(day), "녹화 폴더 구조: " + day);
        LiveJudgement.Outcome before = judge(parallel(2));
        copyTree(day, extra);
        assertThat(judge(parallel(2)).report()).isEqualTo(before.report()).contains("녹화 리플레이 2일");
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(f, t);
                }
            }
        }
    }

    @Test
    void differentLiveReportIsAnIncident() throws Exception {
        operate(true);
        Path live = experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE);
        Files.writeString(live, Files.readString(live).replaceFirst("status=COMPLETED", "status=FAILED"), StandardCharsets.UTF_8);

        LiveJudgement.Outcome out = judge(parallel(2));

        assertThat(out.incident()).isTrue();
        assertThat(out.report()).contains("- I3 실시간 = 입력 기록 리플레이: **다름**");
    }

    @Test
    void missingRecordingDayLeavesL11NotAvailable() throws Exception {
        operate(false);

        LiveJudgement.Outcome out = judge(parallel(2));

        assertThat(out.incident()).isFalse();
        assertThat(out.report()).contains("녹화 리플레이 없음(같은 날 녹화 없음: 2026-10-14, 2026-10-15)")
                .contains("- L11 실시간 대 녹화: 수익률 차 ≤ 2.00%p, 체결 수 차 ≤ 10.00%: 판정 불가 — 같은 날 녹화 없음: 2026-10-14, 2026-10-15");
    }

    @Test
    void writesTheJudgementNextToTheRegistration() throws Exception {
        operate(true);
        LiveJudgement.Outcome out = LiveJudgement.runAndWrite(experimentDir, REPO, sessions, recordings,
                new CodeVersion("judge-commit", false), m -> { }, parallel(2));
        assertThat(Files.readString(experimentDir.resolve(LiveJudgement.FILE))).isEqualTo(out.report());
    }

    @Test
    void needsBaselineControlAndJudgedProfiles() throws Exception {
        writePlan(List.of("kr-bh-baseline@1", "kr-orb-30@1"));
        operate(false);
        assertThatThrownBy(() -> judge(RunGate.NONE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대조군(logic: random)");
    }

    @Test
    void needsTheLiveReportAndAClosedSession() throws Exception {
        assertThatThrownBy(() -> judge(RunGate.NONE)).hasMessageContaining(LiveSession.LIVE_DETAIL_FILE);
        operate(false);
        LiveSession open = LiveSession.open(plan, REPO, sessions, clock, m -> { });
        try {
            assertThatThrownBy(() -> judge(RunGate.NONE)).hasMessageContaining("실험실이 이 세션을 열고 있습니다");
        } finally {
            open.close();
        }
    }

    /** 명령 본체의 종료 코드: 판정함 0, 무사고 조건이 깨지면 2(파일은 씀), 입력이 없으면 1(PR #180 architect R1 (c)). */
    @Test
    void mainExitCodes() throws Exception {
        copyProfiles(List.of("kr-bh-baseline", "kr-random-control", "kr-orb-30", "kr-vwap-reclaim"));
        String[] args = {tmp.toString(), "judge-commit", "false", ID, "--sessions-dir", sessions.toString(),
                "--recordings-dir", recordings.toString()};

        assertThat(LiveJudgeMain.run(args, tmp.resolve("config"), parallel(2))).isEqualTo(1); // live-detail.txt 없음
        assertThat(experimentDir.resolve(LiveJudgement.FILE)).doesNotExist();

        operate(true);
        assertThat(LiveJudgeMain.run(args, tmp.resolve("config"), parallel(2))).isEqualTo(0);
        assertThat(experimentDir.resolve(LiveJudgement.FILE)).exists();

        Path live = experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE);
        Files.writeString(live, Files.readString(live).replaceFirst("status=COMPLETED", "status=FAILED"), StandardCharsets.UTF_8);
        Files.delete(experimentDir.resolve(LiveJudgement.FILE));
        assertThat(LiveJudgeMain.run(args, tmp.resolve("config"), parallel(2))).isEqualTo(2);
        assertThat(Files.readString(experimentDir.resolve(LiveJudgement.FILE))).contains("**다름**");
    }

    /** 명령은 저장소 루트 하나로 등록 파일과 프로필을 함께 찾으므로 시험 루트에 프로필을 옮겨 둔다. */
    private void copyProfiles(List<String> names) throws Exception {
        for (String name : names) {
            Path from = REPO.resolve("strategies").resolve(name);
            try (var files = Files.walk(from)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Path to = tmp.resolve("strategies").resolve(name).resolve(from.relativize(f).toString());
                    if (Files.isDirectory(f)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(f, to);
                    }
                }
            }
        }
    }

    @Test
    void stopsWithoutWritingWhenTheGateSaysSo() throws Exception {
        operate(true);
        AtomicInteger checks = new AtomicInteger();
        RunGate stopLater = () -> checks.incrementAndGet() > 5 ? Optional.of("정지 요청 파일") : Optional.empty();
        assertThatThrownBy(() -> LiveJudgement.runAndWrite(experimentDir, REPO, sessions, recordings,
                new CodeVersion("c", false), m -> { }, stopLater)).isInstanceOf(ExperimentStoppedException.class);
        assertThat(experimentDir.resolve(LiveJudgement.FILE)).doesNotExist();
    }
}
