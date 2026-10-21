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
        writePlan(profiles, "");
    }

    /** @param extra boards 뒤에 붙일 줄(새 키 시험) */
    private void writePlan(List<String> profiles, String extra) throws Exception {
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
        yaml.append(extra);
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

    /**
     * 참고 계좌(ADR-014 F1)와 묶음별 대조군 시드 수(F3): 참고 계좌는 m·변형·지연·대조군 시드에서 빠지고 L7·L9·L10이 "–"다. 판정 결과
     * 맵에도 넣지 않는다. 시드 수를 늘려도 앞 21m − 1개 레인은 키가 없을 때와 같은 레인이다.
     */
    @Test
    void referenceAccountsAreReportedButNotJudgedAndGroupSeedsOnlyAddLanes() throws Exception {
        List<String> profiles = List.of("kr-bh-baseline@1", "kr-random-control@1", "kr-orb-30@1", "kr-vwap-reclaim@1");
        writePlan(profiles, "references:\n  - \"kr-vwap-reclaim@1\"\ngroup-null-seeds:\n  - \"none 25\"\n");
        operate(true);

        LiveJudgement.Outcome out = judge(parallel(4));

        assertThat(out.verdicts()).containsOnlyKeys(ID + "-A01", ID + "-A02", ID + "-A03");
        String r = out.report();
        assertThat(r).contains("- 판정 대상 m = 1, 대조군 시드 N(묶음별, 실험 시드 + 1 ~ + N): 선정 없음 25(등록 파일 "
                        + "`group-null-seeds`); 적지 않은 묶음은 21m − 1 = 20(판정 가능 최소 19에 여유 1), L10 문턱 0.05/m = 0.05000")
                .contains("- 참고 계좌 1개(판정하지 않음, m에 넣지 않음, 등록 파일 `references`): " + ID + "-A04. ")
                // 레인: BASE 4 + PERTURB 2(orb만) + 지연 3 × 1 + 대조군 25 = Run 34, 레인 1 + 1 + 3 + 25
                .contains("입력 기록 레인 30개(Run 34개), ")
                .contains("| " + ID + "-A03 | kr-orb-30@1 | 판정 대상 | **")
                .containsPattern("\\| " + ID + "-A04 \\| kr-vwap-reclaim@1 \\| 참고 \\| \\(참고\\) [^|]+(\\| [^|]+){6}"
                        + "\\| – \\| [^|]+ \\| – \\| – \\| [^|]+ \\|")
                .contains("- 참고 계좌는 판정하지 않는다(등록 파일 `references`, ADR-014 F1).")
                .contains("### " + ID + "-A04 kr-vwap-reclaim@1 (참고, ")
                .contains("- L7·L9·L10: – 돌리지 않음(참고 계좌. m·변형·지연·대조군 시드에서 뺌, ADR-014 3.2)")
                .contains("## 9. 대조군 품질(ADR-014 4.2)")
                .contains("| 선정 없음 | " + ID + "-A02 | 25 | ")
                .doesNotContain("signal.dip-pct ×0.8"); // 참고 계좌는 변형을 돌리지 않는다
        String referenceSection = r.substring(r.indexOf("### " + ID + "-A04 "), r.indexOf("## 4."));
        assertThat(referenceSection).doesNotContain("- L7 ").doesNotContain("- L9 ").doesNotContain("- L10 ")
                .contains("- L8 ").contains("- L11 ");
        // 지연 민감도 표에는 판정 대상만
        String latency = r.substring(r.indexOf("## 5."), r.indexOf("## 6."));
        assertThat(latency).contains("| kr-orb-30 | ").doesNotContain("kr-vwap-reclaim");
        assertThat(judge(parallel(1)).report()).isEqualTo(r);

        // 같은 계좌 구성에서 참고 계좌만 있고 group-null-seeds가 없을 때의 레인(m = 1 → N = 20)과 비교: 시드 레인은 ID·시드·계좌가 앞에서부터 같다
        LiveSetup keyed = LiveSetup.of(plan, REPO);
        JudgeRoles.Roles keyedRoles = LiveJudgement.roles(keyed);
        LiveJudgement.Plan keyedLanes = LiveJudgement.lanes(keyed, keyedRoles, "v",
                LiveJudgement.nullSeeds(keyedRoles.m()), m -> { });
        writePlan(profiles, "references:\n  - \"kr-vwap-reclaim@1\"\n");
        LiveSetup plain = LiveSetup.of(plan, REPO);
        JudgeRoles.Roles plainRoles = LiveJudgement.roles(plain);
        LiveJudgement.Plan plainLanes = LiveJudgement.lanes(plain, plainRoles, "v", LiveJudgement.nullSeeds(plainRoles.m()),
                m -> { });
        assertThat(keyedLanes.nulls()).hasSize(25);
        assertThat(plainLanes.nulls()).hasSize(20);
        assertThat(keyedLanes.nulls().subList(0, 20)).isEqualTo(plainLanes.nulls());
    }

    /** 빈 references·group-null-seeds는 키가 없는 것과 같다: 판정 결과 파일이 바이트로 같다(LIVE01 판정 보호, ADR-014 2장 ⑤). */
    @Test
    void emptyNewKeysLeaveTheJudgementByteForByteUnchanged() throws Exception {
        operate(true);
        String without = judge(parallel(2)).report();
        writePlan(List.of("kr-bh-baseline@1", "kr-random-control@1", "kr-orb-30@1", "kr-vwap-reclaim@1"),
                "references:\ngroup-null-seeds:\n");
        assertThat(judge(parallel(2)).report()).isEqualTo(without);
        assertThat(without).doesNotContain("참고 계좌").doesNotContain("대조군 품질").doesNotContain("N(묶음별")
                .doesNotContain(" | – |");
    }

    /** LIVE01 모양 판정 결과 고정 파일(PR #208 architect m2). 비용 표 해시·데이터 버전 줄은 가린다(비용 표·녹화 형식 변경과 무관하게). */
    private static final String LIVE01_GOLDEN = "/golden/live-judgement-live01.md";

    /**
     * LIVE01 모양 등록(새 키 없음)의 판정 결과는 PR #208 머지(f571a38) 때와 바이트로 같다(PR #208 architect m2, LIVE01 판정 보호).
     * 비용·데이터 버전 줄만 가린다. 판정 계산을 바꿔 이 시험이 깨지면 고정 파일을 고치기 전에 LIVE01 판정에 주는 영향을 적는다.
     * 갱신: {@code ./gradlew :autotrade-sim:test -Dgolden.update=true}.
     */
    @Test
    void live01ShapedJudgementMatchesTheCommittedGolden() throws Exception {
        operate(true);
        String masked = maskVolatile(judge(parallel(2)).report());
        if (Boolean.getBoolean("golden.update")) {
            Path p = Path.of("src/test/resources" + LIVE01_GOLDEN);
            Files.createDirectories(p.getParent());
            Files.writeString(p, masked, StandardCharsets.UTF_8);
        }
        try (java.io.InputStream in = LiveJudgementTest.class.getResourceAsStream(LIVE01_GOLDEN)) {
            assertThat(in).as(LIVE01_GOLDEN).isNotNull();
            assertThat(masked).isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String maskVolatile(String report) {
        StringBuilder sb = new StringBuilder();
        for (String line : report.split("\n", -1)) {
            if (line.startsWith("- 비용: ")) {
                line = "- 비용: <가림>";
            } else if (line.startsWith("- 데이터 버전: ")) {
                line = "- 데이터 버전: <가림>";
            }
            sb.append(line).append('\n');
        }
        return sb.substring(0, sb.length() - 1);
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

    @Test
    void run_all_returns_results_in_task_order_whatever_finishes_first() throws Exception {
        java.util.concurrent.CountDownLatch secondDone = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Callable<String>> tasks = List.of(() -> {
            // 첫 작업은 둘째가 끝난 뒤에 끝난다
            assertThat(secondDone.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return "G1";
        }, () -> {
            secondDone.countDown();
            return "G2";
        });
        assertThat(LiveJudgement.runAll(tasks, "T", () -> { })).containsExactly("G1", "G2");
    }

    @Test
    void run_all_raises_a_later_group_failure_without_waiting_for_earlier_groups() {
        // PR #180 architect N2: 뒤 묶음이 먼저 실패하면 앞 묶음이 끝날 때까지 기다리지 않는다. 앞 묶음은 판정 문처럼
        // 멈춤 신호를 확인 지점에서 보고 멈춘다(실행기는 스레드 중단을 보지 않으므로 취소만으로는 멈추지 않는다)
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        RunGate gate = LiveJudgement.abortable(RunGate.NONE, failed);
        java.util.concurrent.atomic.AtomicBoolean ranToEnd = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean stoppedByGate = new java.util.concurrent.atomic.AtomicBoolean();
        List<java.util.concurrent.Callable<String>> tasks = List.of(() -> {
            for (int i = 0; i < 4_000; i++) { // 확인 지점 사이 5ms, 멈추지 않으면 20초
                try {
                    gate.check();
                } catch (ExperimentStoppedException e) {
                    stoppedByGate.set(true);
                    throw e;
                }
                sleepUninterruptibly(5);
            }
            ranToEnd.set(true);
            return "G1";
        }, () -> {
            throw new IllegalArgumentException("G2 입력 오류");
        });
        assertThatThrownBy(() -> LiveJudgement.runAll(tasks, "T", () -> failed.set(true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("G2 입력 오류");
        assertThat(stoppedByGate).isTrue();
        assertThat(ranToEnd).isFalse();
    }

    @Test
    void abortable_gate_stops_once_failed_and_otherwise_follows_the_original_gate() {
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger planned = new AtomicInteger();
        RunGate original = new RunGate() {
            @Override
            public Optional<String> stopReason() {
                return Optional.empty();
            }

            @Override
            public void onExperimentsPlanned(int count) {
                planned.set(count);
            }

            @Override
            public int maxParallelRuns() {
                return 3;
            }
        };
        RunGate gate = LiveJudgement.abortable(original, failed);
        gate.check();
        assertThat(gate.stopReason()).isEmpty();
        assertThat(gate.maxParallelRuns()).isEqualTo(3);
        gate.onExperimentsPlanned(7);
        assertThat(planned).hasValue(7);

        failed.set(true);
        assertThat(gate.stopReason()).contains("다른 판정 묶음이 실패해 멈춤");
        assertThatThrownBy(gate::check).isInstanceOf(ExperimentStoppedException.class);

        // 원래 문의 멈춤·취소는 그대로 따른다
        RunGate stopping = LiveJudgement.abortable(() -> Optional.of("장 시간"),
                new java.util.concurrent.atomic.AtomicBoolean());
        assertThatThrownBy(stopping::check).isInstanceOf(ExperimentStoppedException.class).hasMessageContaining("장 시간");
    }

    @Test
    void abortable_stream_stops_at_the_next_event_once_failed_and_otherwise_passes_everything_through() {
        // PR #185 architect N1: 문은 Run 시작·거래일 바뀜에서만 확인되므로, 기록 읽기가 이벤트마다 실패 표시를 봐야
        // 한 거래일을 더 돌거나 Run이 모두 끝난 묶음이 남은 기록을 끝까지 읽지 않는다
        List<MarketEvent> events = LiveSessionTest.twoDays();
        com.autotrade.sim.run.MarketEventStream source = new com.autotrade.sim.run.MarketEventStream() {
            @Override
            public String version() {
                return "v1";
            }

            @Override
            public Market market() {
                return Market.KRX;
            }

            @Override
            public boolean exchangeTimed() {
                return false;
            }

            @Override
            public long eventCount() {
                return events.size();
            }

            @Override
            public void forEach(java.util.function.Consumer<MarketEvent> sink) {
                events.forEach(sink);
            }
        };
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        com.autotrade.sim.run.MarketEventStream stream = LiveJudgement.abortable(source, failed);
        assertThat(stream.version()).isEqualTo("v1");
        assertThat(stream.market()).isEqualTo(Market.KRX);
        assertThat(stream.exchangeTimed()).isFalse();
        assertThat(stream.eventCount()).isEqualTo(events.size());
        List<MarketEvent> all = new java.util.ArrayList<>();
        stream.forEach(all::add);
        assertThat(all).isEqualTo(events);

        // 한 거래일 안(첫 날 10번째 이벤트)에서 실패 표시가 서면 바로 다음 이벤트를 넘기지 않는다
        List<MarketEvent> seen = new java.util.ArrayList<>();
        assertThatThrownBy(() -> stream.forEach(e -> {
            seen.add(e);
            if (seen.size() == 10) {
                failed.set(true);
            }
        })).isInstanceOf(ExperimentStoppedException.class).hasMessageContaining("다른 판정 묶음이 실패해 멈춤");
        assertThat(seen).hasSize(10);
    }

    @Test
    void abortable_wrappers_override_every_method_of_their_interface() {
        // PR #185 strategy-researcher n2: 인터페이스에 기본 메서드가 더해지면 감싼 쪽이 위임하지 않아 조용히 기본값이 된다
        assertOverridesAll(RunGate.class, LiveJudgement.abortable(RunGate.NONE,
                new java.util.concurrent.atomic.AtomicBoolean()).getClass());
        com.autotrade.sim.run.MarketEventStream empty = new com.autotrade.sim.run.MarketEventStream() {
            @Override
            public String version() {
                return "v";
            }

            @Override
            public Market market() {
                return Market.KRX;
            }

            @Override
            public long eventCount() {
                return 0;
            }

            @Override
            public void forEach(java.util.function.Consumer<MarketEvent> sink) {
            }
        };
        assertOverridesAll(com.autotrade.sim.run.MarketEventStream.class,
                LiveJudgement.abortable(empty, new java.util.concurrent.atomic.AtomicBoolean()).getClass());
    }

    private static void assertOverridesAll(Class<?> api, Class<?> impl) {
        for (java.lang.reflect.Method m : api.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertThat(java.util.Arrays.stream(impl.getDeclaredMethods())
                    .anyMatch(d -> d.getName().equals(m.getName())
                            && java.util.Arrays.equals(d.getParameterTypes(), m.getParameterTypes())))
                    .as(api.getSimpleName() + "." + m.getName() + "을 위임하지 않음").isTrue();
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 취소의 스레드 중단은 무시한다(실제 실행기와 같게)
        }
    }
}
