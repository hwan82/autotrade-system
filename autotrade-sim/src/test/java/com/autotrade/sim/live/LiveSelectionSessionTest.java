package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSelectionDay;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.SelectionFixtures;
import com.autotrade.sim.selection.SelectionPreparer;
import com.autotrade.sim.selection.SelectionResultFile;
import com.autotrade.sim.selection.SessionSelections;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실시간 모의의 선정 조합 계좌(D-124, ADR-012 3.5·3.6): 그날 첫 이벤트를 기록하기 전에 적용 기록을 쓰고, 선정 계좌는 그날
 * 후보 ∩ boards만 새로 사며, 결과가 없는 날은 새로 사지 않는다. 재기동·리플레이 확인은 같은 적용 기록을 읽어 같은 결과를 낸다.
 * 선정이 없는 계좌는 선정 계좌를 더해도 결과가 같다.
 */
class LiveSelectionSessionTest {

    private static final Path REPO = Path.of("..");
    private static final String ID = "EXP-TEST-LIVESEL";
    private static final LocalDate D1 = LocalDate.of(2026, 10, 14);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 15);
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final SelectionResultFile.Producer PRODUCER = new SelectionResultFile.Producer("prep", false, null);

    @TempDir
    Path tmp;

    final LiveSessionTest.Mutable clock = new LiveSessionTest.Mutable();
    SelectionProfile hot;

    @BeforeEach
    void setUp() {
        hot = SelectionFixtures.intradayHot(REPO);
    }

    private static String yaml(boolean withSelections) {
        StringBuilder sb = new StringBuilder(String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"선정 계좌가 그날 후보만 사는가\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-15\"",
                "seed: \"20261014\"",
                "seed-capital: \"10000000\"",
                "profiles:",
                "  - \"kr-orb-30@1\"",
                "  - \"kr-random-control@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "  - \"000660 KOSPI\"",
                ""));
        if (withSelections) {
            sb.append(String.join("\n",
                    "selections:",
                    "  - \"kr-intraday-hot@1 x kr-random-control@1\"",
                    "selection-input:",
                    "  daily: \"latest\"",
                    ""));
        }
        return sb.toString();
    }

    private Path experimentDir(Path root, boolean withSelections) throws Exception {
        Path dir = root.resolve("experiments").resolve(ID);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("experiment.yaml"), yaml(withSelections), StandardCharsets.UTF_8);
        return dir;
    }

    private static LivePlan plan(boolean withSelections) {
        return LivePlan.parse(yaml(withSelections), "experiment.yaml");
    }

    /** 준비 작업 대신 D1 결과를 놓는다: 005930(boards 안)과 035720(boards 밖). D2는 결과 없음. */
    private SessionSelections prepareDayOne(Path sessions) {
        LiveSetup setup = LiveSetup.of(plan(true), REPO);
        SessionSelections files = setup.selectionFiles(sessions.resolve(ID));
        files.writeResult(SelectionFixtures.result(hot, D1, List.of("005930", "035720")), PRODUCER);
        return files;
    }

    @Test
    void selectionAccountBuysOnlyTodaysCandidatesAndReplaysTheSame() throws Exception {
        Path root = tmp.resolve("with");
        Path experimentDir = experimentDir(root, true);
        Path sessions = root.resolve("live-sessions");
        SessionSelections files = prepareDayOne(sessions);
        List<MarketEvent> events = LiveSessionTest.twoDays();
        int restartAt = events.size() / 3; // D1 중간 재기동: 적용 기록을 확인하고 이어 받는다
        boolean wroteLate = false;
        LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { });
        assertThat(s.hasSelections()).isTrue();
        for (int n = 0; n < events.size(); n++) {
            MarketEvent e = events.get(n);
            clock.now = e.receivedAt();
            if (n == restartAt) {
                s.close();
                s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { });
            }
            assertThat(s.accept("KIWOOM-WS", e)).isEqualTo(LiveSession.Accepted.ACCEPTED);
            if (!wroteLate && e.exchangeTime().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().equals(D2)) {
                // D2 첫 이벤트 뒤에 온 결과는 그날 쓰지 않는다(적용 기록이 이미 SELECTION_MISSING)
                files.writeResult(SelectionFixtures.result(hot, D2, List.of("005930")), PRODUCER);
                wroteLate = true;
            }
        }
        assertThat(wroteLate).isTrue();
        assertThat(s.status().selections()).isNotNull();
        assertThat(s.status().selections().date()).isEqualTo(D2);
        assertThat(s.appliedSelections(D1)).hasValueSatisfying(a -> assertThat(a.item(hot.selectionHash())
                .candidates().candidates()).containsExactly("005930"));
        assertThat(s.appliedSelections(D2)).hasValueSatisfying(a -> assertThat(a.item(hot.selectionHash()).reason())
                .isEqualTo(SelectionInvalidReason.MISSING));

        SimulationReport live = s.finish(new CodeVersion("live", false));
        assertThat(live.runs()).hasSize(3);
        RunReport sel = live.runs().get(2);
        assertThat(sel.combination().hasSelection()).isTrue();
        assertThat(sel.selectionDays()).extracting(RunSelectionDay::selection)
                .satisfiesExactly(
                        d -> {
                            assertThat(d.date()).isEqualTo(D1);
                            assertThat(d.isValid()).isTrue();
                            assertThat(d.symbols()).containsExactly(SAMSUNG);
                        },
                        d -> {
                            assertThat(d.date()).isEqualTo(D2);
                            assertThat(d.reason()).isEqualTo(SelectionInvalidReason.MISSING);
                        });
        // 새로 산 것은 D1의 005930뿐이다(boards 밖 035720은 시세가 없고, 결과가 없는 D2는 신규 진입 0)
        assertThat(sel.fills()).filteredOn(f -> f.fill().side() == Side.BUY)
                .allSatisfy(f -> {
                    assertThat(f.fill().symbol()).isEqualTo(SAMSUNG);
                    assertThat(f.fill().exchangeDate()).isEqualTo(D1);
                });

        Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), ReportWriter.render(live),
                StandardCharsets.UTF_8);
        LiveReplayCheck.Outcome out = LiveReplayCheck.run(experimentDir, REPO, sessions, new CodeVersion("check", true),
                m -> { }, RunGate.NONE);
        assertThat(out.same()).isTrue();
        assertThat(out.files().get("inputs.yaml")).contains("selection-input:").contains("selection-days:");
        assertThat(out.files().get("results.md")).contains("## 선정(D-124)").contains("SELECTION_MISSING");

        // 선정 없는 계좌는 선정 계좌를 더하지 않은 세션(같은 자리에서 재기동)과 결과가 같다(계좌 번호·시드가 그대로)
        Path plainRoot = tmp.resolve("plain");
        experimentDir(plainRoot, false);
        Path plainSessions = plainRoot.resolve("live-sessions");
        LiveSession plain = LiveSession.open(plan(false), REPO, plainSessions, clock, m -> { });
        assertThat(plain.hasSelections()).isFalse();
        for (int n = 0; n < events.size(); n++) {
            clock.now = events.get(n).receivedAt();
            if (n == restartAt) {
                plain.close();
                plain = LiveSession.open(plan(false), REPO, plainSessions, clock, m -> { });
            }
            plain.accept("KIWOOM-WS", events.get(n));
        }
        SimulationReport base = plain.finish(new CodeVersion("live", false));
        assertThat(base.runs()).hasSize(2);
        assertThat(Files.exists(plainSessions.resolve(ID).resolve(SessionSelections.DIR))).isFalse();
        for (int i = 0; i < 2; i++) {
            assertThat(live.runs().get(i).summary().runId()).isEqualTo(base.runs().get(i).summary().runId());
            assertThat(live.runs().get(i).fills()).isEqualTo(base.runs().get(i).fills());
            assertThat(live.runs().get(i).rejections()).isEqualTo(base.runs().get(i).rejections());
        }
    }

    @Test
    void preparedFromLatestSnapshotAndReplayRecomputesIt() throws Exception {
        Path root = tmp.resolve("prep");
        Path experimentDir = experimentDir(root, true);
        Path sessions = root.resolve("live-sessions");
        Path snapshots = SelectionFixtures.dailySnapshot(root.resolve("snapshots"), "krx-daily-live-0001", D1.minusDays(1),
                false);
        // D1 준비: latest = 마지막 거래일이 기준 데이터일(10-13)을 덮는 스냅샷. D2는 덮는 스냅샷이 없어 STALE_INPUT
        LiveSelectionPrep.Report d1 = LiveSelectionPrep.prepare(plan(true), REPO, sessions, snapshots, D1, PRODUCER,
                RunGate.NONE, rows -> true, m -> { });
        assertThat(d1.ready()).isTrue();
        assertThat(d1.dailySnapshot()).isEqualTo("krx-daily-live-0001");
        assertThat(d1.outcomes()).singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(SelectionPreparer.Status.WRITTEN));
        assertThat(LiveSelectionPrep.prepare(plan(true), REPO, sessions, snapshots, D1, PRODUCER, RunGate.NONE,
                rows -> true, m -> { }).note()).startsWith("이미 준비됨");
        LiveSelectionPrep.Report d2 = LiveSelectionPrep.prepare(plan(true), REPO, sessions, snapshots, D2, PRODUCER,
                RunGate.NONE, rows -> true, m -> { });
        assertThat(d2.ready()).isFalse();
        assertThat(d2.outcomes()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT));
        // 힙이 모자라면 읽지 않고 준비 명령을 쓰라고 남긴다
        LiveSelectionPrep.Report small = LiveSelectionPrep.prepare(plan(true), REPO, tmp.resolve("other"), snapshots, D1,
                PRODUCER, RunGate.NONE, rows -> false, m -> { });
        assertThat(small.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.reason()).isEqualTo(SelectionInvalidReason.PRODUCER_FAILED);
            assertThat(o.detail()).contains("runLiveSelectionPrepare");
        });

        LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { });
        for (MarketEvent e : LiveSessionTest.twoDays()) {
            clock.now = e.receivedAt();
            s.accept("KIWOOM-WS", e);
        }
        assertThat(s.appliedSelections(D1)).hasValueSatisfying(a -> assertThat(a.item(hot.selectionHash())
                .candidates().candidates()).containsExactly("005930"));
        assertThat(s.appliedSelections(D2)).hasValueSatisfying(a -> assertThat(a.item(hot.selectionHash()).reason())
                .isEqualTo(SelectionInvalidReason.STALE_INPUT));
        SimulationReport live = s.finish(new CodeVersion("live", false));
        Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), ReportWriter.render(live),
                StandardCharsets.UTF_8);

        LiveReplayCheck.Outcome out = LiveReplayCheck.run(experimentDir, REPO, sessions, snapshots,
                new CodeVersion("check", true), m -> { }, RunGate.NONE);
        assertThat(out.same()).isTrue();
        assertThat(out.selectionsRecomputed()).isTrue();
        assertThat(out.files().get("inputs.yaml")).contains("selection-recompute: \"같음 1, 다름 0, 재계산 생략 0\"");
        // 스냅샷 폴더를 주지 않으면 생략으로 적는다(다름이 아님)
        LiveReplayCheck.Outcome noSnap = LiveReplayCheck.run(experimentDir, REPO, sessions, new CodeVersion("check", true),
                m -> { }, RunGate.NONE);
        assertThat(noSnap.selectionsRecomputed()).isTrue();
        assertThat(noSnap.files().get("inputs.yaml")).contains("재계산 생략 1");
        // 같은 ID의 스냅샷 내용이 바뀌면 계좌는 고정 파일로 같게 돌지만 재계산은 다름으로 잡힌다
        SelectionFixtures.dailySnapshot(root.resolve("snapshots"), "krx-daily-live-0001", D1.minusDays(1), true);
        LiveReplayCheck.Outcome changed = LiveReplayCheck.run(experimentDir, REPO, sessions, snapshots,
                new CodeVersion("check", true), m -> { }, RunGate.NONE);
        assertThat(changed.same()).isTrue();
        assertThat(changed.selectionsRecomputed()).isFalse();
        assertThat(changed.files().get("inputs.yaml")).contains("다름 1");
    }

    @Test
    void sessionDoesNotOpenWhenAppliedResultFileChanged() throws Exception {
        Path sessions = tmp.resolve("live-sessions");
        SessionSelections files = prepareDayOne(sessions);
        List<MarketEvent> events = LiveSessionTest.twoDays();
        try (LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { })) {
            for (int n = 0; n < 50; n++) {
                clock.now = events.get(n).receivedAt();
                s.accept("KIWOOM-WS", events.get(n));
            }
        }
        Path result = files.resultFile(D1, hot.selectionHash());
        Files.writeString(result, SelectionResultFile.write(SelectionFixtures.result(hot, D1, List.of("000660")),
                PRODUCER), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> LiveSession.open(plan(true), REPO, sessions, clock, m -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("내용 해시");
    }

    @Test
    void sessionStopsWithoutRecordingWhenAppliedRecordCannotBeWritten() throws Exception {
        Path sessions = tmp.resolve("live-sessions");
        prepareDayOne(sessions);
        // 적용 기록 자리에 폴더를 두어 쓰기를 실패시킨다
        Files.createDirectories(sessions.resolve(ID).resolve(SessionSelections.DIR).resolve(D1.toString())
                .resolve(SessionSelections.APPLIED_FILE).resolve("blocker"));
        List<MarketEvent> events = LiveSessionTest.twoDays();
        try (LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { })) {
            clock.now = events.get(0).receivedAt();
            assertThat(s.accept("KIWOOM-WS", events.get(0))).isEqualTo(LiveSession.Accepted.STOPPED);
            assertThat(s.status().stopReason()).contains("선정 적용 기록");
            assertThat(s.status().events()).isZero();
        }
    }

    @Test
    void registrationRejectsDisclosuresAndRecordingRejectsLatest() {
        String disclosures = yaml(true).replace("  daily: \"latest\"\n", "  daily: \"latest\"\n  disclosures: \"latest\"\n");
        assertThatThrownBy(() -> LivePlan.parse(disclosures, "x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disclosures");
        String noInput = yaml(true).replace("selection-input:\n  daily: \"latest\"\n", "");
        assertThatThrownBy(() -> LivePlan.parse(noInput, "x")).isInstanceOf(IllegalArgumentException.class);
        String duplicate = yaml(true).replace("  - \"kr-intraday-hot@1 x kr-random-control@1\"\n",
                "  - \"kr-intraday-hot@1 x kr-random-control@1\"\n  - \"kr-intraday-hot@1 x kr-random-control@1\"\n");
        assertThatThrownBy(() -> LivePlan.parse(duplicate, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(plan(true).selections().entries()).singleElement()
                .satisfies(e -> assertThat(e.text()).isEqualTo("kr-intraday-hot@1 x kr-random-control@1"));
        assertThat(plan(false).selections().entries()).isEmpty();
    }

    @Test
    void prepareTargetsNextTradingDayOutsideSessionHours() {
        var calendar = com.autotrade.marketdata.calendar.ExchangeCalendar.bundled(com.autotrade.domain.market.Market.KRX);
        // 수요일 07:00(현지) → 그날, 수요일 17:00 → 목요일, 금요일 17:00 → 다음 주 월요일
        assertThat(LiveSelectionPrep.targetDate(calendar, java.time.Instant.parse("2026-10-13T22:00:00Z"))).isEqualTo(D1);
        assertThat(LiveSelectionPrep.targetDate(calendar, java.time.Instant.parse("2026-10-14T08:00:00Z"))).isEqualTo(D2);
        assertThat(LiveSelectionPrep.targetDate(calendar, java.time.Instant.parse("2026-10-16T08:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(LiveSelectionPrep.inQuietHours(calendar, java.time.Instant.parse("2026-10-14T03:00:00Z"))).isFalse();
        assertThat(LiveSelectionPrep.inQuietHours(calendar, java.time.Instant.parse("2026-10-14T08:00:00Z"))).isTrue();
        assertThat(LiveSelectionPrep.inQuietHours(calendar, java.time.Instant.parse("2026-10-17T03:00:00Z"))).isTrue();
    }
}
