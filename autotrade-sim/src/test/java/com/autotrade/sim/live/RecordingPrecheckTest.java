package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.selection.SelectionFixtures;
import com.autotrade.sim.selection.SessionSelections;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureRecordings;
import com.autotrade.sim.testing.TestKrxRules;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 녹화 사전 검사(ADR-014 5장 F4, 9.3 시험 목록): 등록 파일 거절, P2·P3 정수 비교 경계, 구간 값, 실녹화 고정 자료로 끝까지 돌린
 * {@code precheck.md}·{@code inputs.yaml}과 결정론(같은 입력이면 바이트로 같음, 묶음 수와 상관없음).
 */
class RecordingPrecheckTest {

    private static final Path REPO = Path.of("..");
    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final List<MarketEvent> EVENTS =
            FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, TestKrxRules.INSTANCE.zone());
    private static final LocalDate D1 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 4);
    private static final String SNAPSHOT = "krx-daily-sel-0001";
    private static final String ID = "EXP-T-PRE";
    private static final BigDecimal SEED = new BigDecimal("10000000");

    /** 계좌: 0 orb, 1 대조군, 2 vwap(참고), 3 hot x orb, 4 hot x 대조군, 5 momentum x orb(참고, 장중 순위 선정). */
    static final String PLAN = """
            id: "EXP-T-PRE"
            question: "사전 검사 시험"
            input: "recording-precheck"
            market: "KRX"
            from: "2026-03-03"
            to: "2026-03-04"
            seed: "7"
            seed-capital: "10000000"
            parallelism: "2"
            profiles:
              - "kr-orb-30@1"
              - "kr-random-control@1"
              - "kr-vwap-reclaim@1"
            boards:
              - "005930 KOSPI"
              - "000660 KOSPI"
            selections:
              - "kr-intraday-hot@1 x kr-orb-30@1"
              - "kr-intraday-hot@1 x kr-random-control@1"
              - "kr-intraday-open-momentum@1 x kr-orb-30@1"
            selection-input:
              daily: "krx-daily-sel-0001"
            references:
              - "kr-vwap-reclaim@1"
              - "kr-intraday-open-momentum@1 x kr-orb-30@1"
            group-null-seeds:
              - "none 4"
              - "kr-intraday-hot@1 4"
            family-size: "3"
            alpha: "0.75"
            min-round-trips: "1"
            p2:
              - "kr-orb-30@1"
              - "kr-intraday-hot@1 x kr-orb-30@1"
            p3:
              - "kr-orb-30@1 vs kr-vwap-reclaim@1"
              - "kr-intraday-hot@1 x kr-orb-30@1 vs kr-intraday-open-momentum@1 x kr-orb-30@1"
            p3-min-segments: "1"
            segments:
              - "2026-03-03 2026-03-03"
              - "2026-03-04 2026-03-04"
            """;

    @TempDir
    Path tmp;

    private static PrecheckPlan parse(String text) {
        return PrecheckPlan.parse(text, "f");
    }

    /** PLAN의 한 줄을 바꾼 등록. */
    private static String with(String from, String to) {
        assertThat(PLAN).contains(from);
        return PLAN.replace(from, to);
    }

    private static JudgeRoles.Roles roles(String text) {
        PrecheckPlan plan = parse(text);
        return RecordingPrecheck.roles(plan, LiveSetup.of(plan.live(), REPO));
    }

    @Test
    void registrationParsesAllRecordingKeysAndThePrecheckValuesWithoutDouble() {
        PrecheckPlan plan = parse(PLAN);
        assertThat(plan.recording().parallelism()).isEqualTo(2);
        assertThat(plan.live().references()).hasSize(2);
        assertThat(plan.live().groupNullSeeds()).containsEntry("none", 4).containsEntry("kr-intraday-hot@1", 4);
        assertThat(plan.alpha()).isEqualByComparingTo("0.75");
        assertThat(plan.minPlusOne()).isEqualTo(4);
        assertThat(plan.threshold()).isEqualTo("0.25000");
        assertThat(plan.p3()).extracting(PrecheckPlan.Pair::reference).containsExactly("kr-vwap-reclaim@1",
                "kr-intraday-open-momentum@1 x kr-orb-30@1");
        JudgeRoles.Roles r = roles(PLAN);
        assertThat(r.baseline()).isEqualTo(-1); // 기준선은 선택
        assertThat(r.judged()).containsExactly(0, 3);
        assertThat(r.references()).containsExactly(2, 5);
        assertThat(r.nullControls()).containsExactly(1, 4);
        // 판정 명령의 21m − 1 하한(m = 2 → 41)은 사전 검사에 걸지 않는다
        assertThat(r.seedsFor(1, 0)).isEqualTo(4);
    }

    @Test
    void refusesRegistrationErrorsOfAdr014SectionFiveOne() {
        // 형식·값(프로필을 읽지 않고 아는 것)
        assertThatThrownBy(() -> parse(with("input: \"recording-precheck\"", "input: \"recording\"")))
                .hasMessageContaining("input은 \"recording-precheck\"");
        assertThatThrownBy(() -> parse(with("daily: \"krx-daily-sel-0001\"", "daily: \"latest\"")))
                .hasMessageContaining("latest");
        assertThatThrownBy(() -> parse(with("alpha: \"0.75\"", "alpha: \"1.0\""))).hasMessageContaining("alpha");
        assertThatThrownBy(() -> parse(with("alpha: \"0.75\"", "alpha: \"5e-2\""))).hasMessageContaining("alpha");
        assertThatThrownBy(() -> parse(with("min-round-trips: \"1\"", "min-round-trips: \"0\"")))
                .hasMessageContaining("min-round-trips");
        assertThatThrownBy(() -> parse(with("p3-min-segments: \"1\"", "p3-min-segments: \"3\"")))
                .hasMessageContaining("p3-min-segments는 1 ~ 구간 수(2)");
        assertThatThrownBy(() -> parse(with("p3-min-segments: \"1\"\n", ""))).hasMessageContaining("p3가 있으면");
        assertThatThrownBy(() -> parse(with("  - \"kr-orb-30@1 vs kr-vwap-reclaim@1\"",
                "  - \"kr-orb-30@1 vs kr-intraday-hot@1 x kr-orb-30@1\""))).hasMessageContaining("p3 오른쪽은 참고 계좌");
        assertThatThrownBy(() -> parse(with("  - \"kr-orb-30@1 vs kr-vwap-reclaim@1\"",
                "  - \"kr-orb-30@1 vs kr-vwap-reclaim@1\"\n  - \"kr-orb-30@1 vs kr-vwap-reclaim@1\"")))
                .hasMessageContaining("p3에 같은 줄이 두 번");
        assertThatThrownBy(() -> parse(with("p2:\n  - \"kr-orb-30@1\"", "p2:\n  - \"kr-orb-30@1\"\n  - \"kr-orb-30@1\"")))
                .hasMessageContaining("p2에 같은 계좌가 두 번");
        assertThatThrownBy(() -> parse(with("p2:\n  - \"kr-orb-30@1\"", "p2:\n  - \"kr-orb-30@2\"")))
                .hasMessageContaining("p2 항목이 profiles·selections 줄에 없습니다");
        // 구간: 빈틈, 겹침, 기간 밖, 휴장일 끝, 기간 끝까지 덮지 않음(거래소 캘린더로 셈)
        assertThatThrownBy(() -> parse(with("  - \"2026-03-03 2026-03-03\"\n  - \"2026-03-04 2026-03-04\"",
                "  - \"2026-03-04 2026-03-04\""))).hasMessageContaining("구간 사이에 덮지 않은 거래일이 있습니다: 2026-03-03");
        assertThatThrownBy(() -> parse(with("  - \"2026-03-04 2026-03-04\"", "  - \"2026-03-03 2026-03-04\"")))
                .hasMessageContaining("겹치거나");
        assertThatThrownBy(() -> parse(with("  - \"2026-03-04 2026-03-04\"", "  - \"2026-03-04 2026-03-05\"")))
                .hasMessageContaining("기간(2026-03-03 ~ 2026-03-04) 밖");
        assertThatThrownBy(() -> parse(with("  - \"2026-03-04 2026-03-04\"\n", ""))).hasMessageContaining("마지막 구간 뒤");
        assertThatThrownBy(() -> parse(with("from: \"2026-03-03\"", "from: \"2026-03-02\"")
                .replace("\"2026-03-03 2026-03-03\"", "\"2026-03-02 2026-03-03\""))).hasMessageContaining("거래일이어야");

        // 역할(프로필을 읽어야 아는 것)
        assertThatThrownBy(() -> roles(with("  - \"kr-intraday-hot@1 4\"\n", "")))
                .hasMessageContaining("group-null-seeds를 대조군이 있는 모든 묶음에").hasMessageContaining("선정 kr-intraday-hot@1");
        assertThatThrownBy(() -> roles(with("family-size: \"3\"", "family-size: \"1\"")))
                .hasMessageContaining("family-size 1가 실제 판정 대상 수 2보다 작습니다");
        assertThatThrownBy(() -> roles(with("p2:\n  - \"kr-orb-30@1\"", "p2:\n  - \"kr-vwap-reclaim@1\"")))
                .hasMessageContaining("p2 계좌가 판정 대상이 아닙니다");
        assertThatThrownBy(() -> roles(with("p2:\n  - \"kr-orb-30@1\"", "p2:\n  - \"kr-random-control@1\"")))
                .hasMessageContaining("p2 계좌가 판정 대상이 아닙니다");
        assertThatThrownBy(() -> roles(with("  - \"kr-orb-30@1 vs kr-vwap-reclaim@1\"",
                "  - \"kr-random-control@1 vs kr-vwap-reclaim@1\""))).hasMessageContaining("p3 왼쪽 계좌가 판정 대상이 아닙니다");
        // P2 묶음 N ≥ ⌈family ÷ alpha⌉ − 1 = 3
        assertThatThrownBy(() -> roles(with("  - \"none 4\"", "  - \"none 2\"")))
                .hasMessageContaining("⌈family-size ÷ alpha⌉ − 1 = 3보다 작아");
        // P2 계좌가 아닌 묶음은 N이 작아도 된다(P2를 판정하지 않음)
        roles(with("  - \"none 4\"", "  - \"none 2\"").replace("p2:\n  - \"kr-orb-30@1\"\n", "p2:\n"));
    }

    @Test
    void p2UsesIntegerComparisonWithEqualityPassing() {
        BigDecimal a = new BigDecimal("0.05");
        // family 4, alpha 0.05: ⌈80⌉ → N′ + 1 ≥ 80이어야 판정
        assertThat(RecordingPrecheck.p2Outcome(0, 78, 4, a)).isEqualTo(RecordingPrecheck.NOT_AVAILABLE);
        assertThat(RecordingPrecheck.p2Outcome(0, 79, 4, a)).isEqualTo(RecordingPrecheck.PASS); // 4 ≤ 4(같음은 통과)
        assertThat(RecordingPrecheck.p2Outcome(1, 79, 4, a)).isEqualTo(RecordingPrecheck.FAIL);
        assertThat(RecordingPrecheck.p2Outcome(1, 159, 4, a)).isEqualTo(RecordingPrecheck.PASS); // 8 ≤ 8
        assertThat(RecordingPrecheck.p2Outcome(2, 159, 4, a)).isEqualTo(RecordingPrecheck.FAIL);
        assertThat(RecordingPrecheck.p2Outcome(1, 199, 4, a)).isEqualTo(RecordingPrecheck.PASS);
        // alpha가 나누어떨어지지 않아도 정확한 올림: 3 ÷ 0.07 = 42.86 → 43
        assertThat(RecordingPrecheck.p2Outcome(0, 41, 3, new BigDecimal("0.07"))).isEqualTo(RecordingPrecheck.NOT_AVAILABLE);
        assertThat(RecordingPrecheck.p2Outcome(0, 42, 3, new BigDecimal("0.07"))).isEqualTo(RecordingPrecheck.PASS); // 3 ≤ 3.01
        assertThat(RecordingPrecheck.p2Outcome(1, 42, 3, new BigDecimal("0.07"))).isEqualTo(RecordingPrecheck.FAIL);
    }

    @Test
    void segmentValuesCarryEquityForwardAndCountTripsByExitDate() {
        List<PrecheckPlan.Segment> segs = List.of(new PrecheckPlan.Segment(D1, D1), new PrecheckPlan.Segment(D2, D2),
                new PrecheckPlan.Segment(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 5)));
        List<EquityPoint> curve = List.of(point(D1, "09:00", "10000500"), point(D1, "15:30", "10001000"),
                point(D2, "15:30", "9999000"));
        // 03-05에는 점이 없어 03-04 값을 쓴다(architect m5). 진입 03-03·청산 03-04 왕복은 경계를 넘긴다
        List<RoundTrip> trips = List.of(trip("a", D1, D1, "1000"), trip("b", D1, D2, "-500"), trip("c", D2, D2, "-1500"));
        List<RecordingPrecheck.SegmentValue> v = RecordingPrecheck.segmentValues(curve, trips, segs, SEED);
        assertThat(v.get(0).net()).isEqualByComparingTo("1000");
        assertThat(v.get(0).trips()).isEqualTo(1);
        assertThat(v.get(0).perTrip()).isEqualByComparingTo("1000");
        assertThat(v.get(1).net()).isEqualByComparingTo("-2000");
        assertThat(v.get(1).trips()).isEqualTo(2);
        assertThat(v.get(1).perTrip()).isEqualByComparingTo("-1000");
        assertThat(v.get(2).net()).isEqualByComparingTo("0");
        assertThat(v.get(2).perTrip()).isNull();
        assertThat(RecordingPrecheck.crossing(trips, segs)).isEqualTo(1);
        // 점이 하나도 없으면 시드머니
        assertThat(RecordingPrecheck.dailyEquity(List.of(), D1, SEED)).isEqualByComparingTo(SEED);
        assertThat(RecordingPrecheck.dailyEquity(curve, D1.minusDays(1), SEED)).isEqualByComparingTo(SEED);

        // d_s: 어느 한쪽이 구간 왕복 0건이면 d > 0이 아님
        List<RecordingPrecheck.SegmentValue> ref = List.of(new RecordingPrecheck.SegmentValue(BigDecimal.ZERO, 1,
                new BigDecimal("500")), new RecordingPrecheck.SegmentValue(BigDecimal.ZERO, 0, null),
                new RecordingPrecheck.SegmentValue(BigDecimal.ZERO, 1, new BigDecimal("-1")));
        assertThat(RecordingPrecheck.segmentDifferences(v, ref)).containsExactly(new BigDecimal("500"), null, null);
        assertThat(RecordingPrecheck.positiveSegments(v, ref)).isEqualTo(1);
    }

    private static EquityPoint point(LocalDate d, String time, String equity) {
        Instant ts = d.atTime(java.time.LocalTime.parse(time)).atZone(KRX.zone()).toInstant();
        Money m = Money.of(equity, Money.KRW);
        return new EquityPoint(d, ts, m, m);
    }

    private static RoundTrip trip(String lot, LocalDate entry, LocalDate exit, String pnl) {
        return new RoundTrip(lot, Symbol.krx("005930"), entry, exit,
                Money.of(pnl, Money.KRW));
    }

    private Path experiment(String name, String plan) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name).resolve("experiments").resolve(ID));
        Files.writeString(dir.resolve("experiment.yaml"), plan, StandardCharsets.UTF_8);
        return dir;
    }

    private Path recordings() {
        Path recordings = tmp.resolve("recordings");
        if (!Files.isDirectory(recordings)) {
            FixtureRecordings.record(recordings, Market.KRX, TestKrxRules.INSTANCE.zone(), EVENTS);
        }
        return recordings;
    }

    private Path snapshots() {
        Path root = tmp.resolve("snapshots");
        if (!Files.isDirectory(root.resolve(SNAPSHOT))) {
            SelectionFixtures.dailySnapshot(root, SNAPSHOT, KRX.previousTradingDay(D1), false);
        }
        return root;
    }

    private RecordingPrecheck.Outcome run(Path dir, boolean verify, RunGate gate) {
        return RecordingPrecheck.runAndWrite(dir, REPO, recordings(), snapshots(), new CodeVersion("test", false), verify,
                m -> { }, gate);
    }

    @Test
    void writesPrecheckAndInputsDeterministically() throws IOException {
        Path dir = experiment("a", PLAN);
        RecordingPrecheck.Outcome out = run(dir, true, RunGate.NONE);

        String report = Files.readString(dir.resolve(RecordingPrecheck.FILE), StandardCharsets.UTF_8);
        assertThat(report).isEqualTo(out.files().get(RecordingPrecheck.FILE));
        assertThat(report).startsWith("# EXP-T-PRE 녹화 사전 검사(ADR-014 F4)\n\n> 사전 검사 통과는 승격 근거가 아니다(계획 4.2)")
                .contains("투자 자문이 아니며");
        for (String section : List.of("## 1. 머리말", "## 2. 판정 요약", "## 3. 항목별 근거", "## 4. 구간 표",
                "## 5. 대조군 품질·LIVE02 시드 수", "## 6. 구독 커버리지", "## 7. 한도 도달·거절 사유", "## 8. 선정 표")) {
            assertThat(report).contains("\n" + section + "\n");
        }
        assertThat(report).contains("- 돌린 것: 레인 9개(Run 14개) = BASE 1 + 대조군 시드 선정 없음(none) 4, 선정 kr-intraday-hot@1 4")
                .contains("판정 대상 2개, m_pre = family-size 3, alpha 0.75, P2 문턱 alpha/family-size = 0.25000")
                .contains("기준선 없음")
                .contains("| 참고 |").contains("### 장전 선정(D-124)").contains("### 장중 순위 선정(D-127)")
                .doesNotContain("걸린 시간");
        // 판정 대상 둘만 결과가 있고, 참고 계좌는 결과에 없다
        assertThat(out.verdicts()).hasSize(2);
        assertThat(out.verdicts().values()).allSatisfy(v -> assertThat(v).isIn(RecordingPrecheck.CANDIDATE,
                RecordingPrecheck.FAIL, RecordingPrecheck.NOT_AVAILABLE));
        // 대조군 품질: N = 4인 두 묶음, LIVE02 N 표 4칸
        assertThat(report.lines().filter(l -> l.startsWith("| 선정 없음(none) |") || l.startsWith("| 선정 kr-intraday-hot@1 |"))
                .count()).isGreaterThanOrEqualTo(2);
        // inputs.yaml은 녹화 실험과 같은 모양이고 결정론 확인(묶음 2 vs 1)이 같다
        assertThat(out.files().get(RecordingPrecheck.INPUTS_FILE)).contains("experiment: EXP-T-PRE\n")
                .contains("recording-days:\n").contains("selection-input:\n").contains("intraday-selections:\n")
                .contains("determinism: \"같음(묶음 2 vs 1, precheck.md 바이트 일치)\"");
        assertThat(out.nondeterministic()).isFalse();
        assertThat(out.files().keySet()).anyMatch(k -> k.startsWith(SessionSelections.DIR + "/" + D1 + "/"));
        assertThat(dir.resolve(SessionSelections.DIR)).isDirectory();

        // 같은 입력이면 다른 폴더·묶음 수 1로 돌려도 precheck.md가 바이트로 같다
        Path again = experiment("b", PLAN);
        RecordingPrecheck.Outcome second = run(again, false, maxParallel(1));
        assertThat(second.files().get(RecordingPrecheck.FILE)).isEqualTo(report);
        assertThat(second.files().get(RecordingPrecheck.INPUTS_FILE)).contains("determinism: \"확인 안 함(");
    }

    @Test
    void nullLanesUseTheJudgementIdsAndSeeds() {
        PrecheckPlan plan = parse(PLAN);
        LiveSetup setup = LiveSetup.of(plan.live(), REPO);
        JudgeRoles.Roles roles = RecordingPrecheck.roles(plan, setup);
        List<String> ids = new ArrayList<>();
        List<Long> seeds = new ArrayList<>();
        LiveJudgement.nullLanes(setup, roles, "v", 0).values().forEach(lanes -> lanes.forEach(l -> {
            ids.add(l.spec().id().value());
            seeds.add(l.spec().randomSeed());
        }));
        assertThat(ids).containsExactly("EXP-T-PRE-NULL1", "EXP-T-PRE-NULL2", "EXP-T-PRE-NULL3", "EXP-T-PRE-NULL4",
                "EXP-T-PRE-S1-NULL1", "EXP-T-PRE-S1-NULL2", "EXP-T-PRE-S1-NULL3", "EXP-T-PRE-S1-NULL4");
        assertThat(seeds).containsExactly(8L, 9L, 10L, 11L, 8L, 9L, 10L, 11L);
    }

    @Test
    void stopWritesNothingAndInputErrorsStartNoRun() throws IOException {
        Path dir = experiment("stop", PLAN);
        RunGate stop = new RunGate() {
            @Override
            public Optional<String> stopReason() {
                return Optional.of("시험 정지");
            }
        };
        assertThatThrownBy(() -> run(dir, false, stop)).isInstanceOf(ExperimentStoppedException.class);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("experiment.yaml");
        }
        // 폴더 이름과 실험 ID가 다르면 입력 오류
        Path other = Files.createDirectories(tmp.resolve("x").resolve("EXP-OTHER"));
        Files.writeString(other.resolve("experiment.yaml"), PLAN, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> run(other, false, RunGate.NONE)).hasMessageContaining("폴더 이름과 실험 ID가 다릅니다");
        // 기간 안 녹화가 없으면 입력 오류
        Path late = experiment("late", PLAN.replace("2026-03-03", "2026-03-05").replace("2026-03-04", "2026-03-06"));
        assertThatThrownBy(() -> run(late, false, RunGate.NONE)).hasMessageContaining("기간 안에 녹화가 없습니다");
    }

    @Test
    void baseLaneMatchesTheRecordingExperimentOfTheSameAccounts() throws IOException {
        // BASE 레인은 녹화 실험과 같은 조립·같은 녹화 읽기다: 사전 검사 키를 뺀 같은 등록을 녹화 실험으로 돌리면 상세 보고서가 같다
        Path dir = experiment("base", PLAN);
        RecordingPrecheck.Outcome out = run(dir, false, RunGate.NONE);
        String recording = PLAN.substring(0, PLAN.indexOf("references:")).replace("input: \"recording-precheck\"",
                "input: \"recording\"");
        Path recDir = experiment("rec", recording);
        com.autotrade.sim.recording.RecordingExperiment.Outcome rec = com.autotrade.sim.recording.RecordingExperiment
                .runAndWrite(recDir, REPO, recordings(), snapshots(), new CodeVersion("test", false), false, m -> { },
                        RunGate.NONE);
        assertThat(line(out.files().get(RecordingPrecheck.INPUTS_FILE), "detail-sha256: "))
                .isEqualTo(line(rec.files().get("inputs.yaml"), "detail-sha256: "));
        assertThat(line(out.files().get(RecordingPrecheck.INPUTS_FILE), "data-set: "))
                .isEqualTo(line(rec.files().get("inputs.yaml"), "data-set: "));
    }

    private static String line(String text, String prefix) {
        return text.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void limitsCountFillDaysAgainstTheProfileLimits() {
        com.autotrade.strategy.StrategyProfile orb = com.autotrade.sim.recording.RecordingExperiment.loadStrategy(REPO,
                new com.autotrade.sim.recording.RecordingPlan.ProfileRef("kr-orb-30", 1));
        assertThat(orb.parameters()).containsEntry(RecordingPrecheck.MAX_ENTRIES, "6")
                .containsEntry(RecordingPrecheck.MAX_POSITIONS, "3");
        // D1: 세 종목을 사 보유 3(한도 도달), 하나를 팔고 D2에 다시 사 보유 3(도달). 진입은 날마다 6건 미만
        List<com.autotrade.sim.account.AppliedFill> fills = List.of(fill("005930", D1, com.autotrade.domain.order.Side.BUY),
                fill("000660", D1, com.autotrade.domain.order.Side.BUY), fill("035720", D1, com.autotrade.domain.order.Side.BUY),
                fill("035720", D1, com.autotrade.domain.order.Side.SELL), fill("035420", D2, com.autotrade.domain.order.Side.BUY));
        List<com.autotrade.sim.run.OrderRejection> rejections = List.of(rejection("RISK_DAILY_LOSS"), rejection("CASH"),
                rejection("CASH"));
        com.autotrade.sim.run.RunReport r = new com.autotrade.sim.run.RunReport(new com.autotrade.sim.RunResult("R",
                new com.autotrade.domain.account.AccountId("R"), orb.profileHash(), 1L, com.autotrade.sim.RunStatus.FAILED,
                Money.of(10_000_000L, Money.KRW), "시험(지표 없음)", null), orb, fills, List.of(), List.of(), rejections);
        RecordingPrecheck.Limits l = RecordingPrecheck.limits(r);
        assertThat(l.entryDays()).isEqualTo("0일(한도 6)");
        assertThat(l.positionDays()).isEqualTo("2일(한도 3)");
        assertThat(l.rejections()).containsExactly(java.util.Map.entry("CASH", 2), java.util.Map.entry("RISK_DAILY_LOSS", 1));
    }

    private static com.autotrade.sim.account.AppliedFill fill(String code, LocalDate date, com.autotrade.domain.order.Side side) {
        Symbol s = Symbol.krx(code);
        BigDecimal p = new BigDecimal("10000");
        Instant t = date.atTime(10, 0).atZone(KRX.zone()).toInstant();
        com.autotrade.sim.exchange.Fill f = new com.autotrade.sim.exchange.Fill(
                com.autotrade.domain.order.IdempotencyKey.of(date, s, side, 1), 1, s, side,
                side == com.autotrade.domain.order.Side.BUY ? com.autotrade.domain.order.OrderIntent.ENTRY
                        : com.autotrade.domain.order.OrderIntent.EXIT, 1, p,
                com.autotrade.sim.cost.CostModels.krDefault(x -> com.autotrade.sim.cost.KrxBoard.KOSPI).fees(side, s, date, p, 1),
                Money.zero(Money.KRW), 1, 2, t, t, date);
        return new com.autotrade.sim.account.AppliedFill(f, "lot-" + code, null);
    }

    private static com.autotrade.sim.run.OrderRejection rejection(String code) {
        return new com.autotrade.sim.run.OrderRejection(1, com.autotrade.domain.order.IdempotencyKey.of(D1, Symbol.krx("005930"),
                com.autotrade.domain.order.Side.BUY, 9), Symbol.krx("005930"), "risk", code, "");
    }

    private static RunGate maxParallel(int n) {
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
}
