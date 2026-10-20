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
    void preparationDoesNotReloadTheSnapshotAfterFailingWithTheSameInputs() throws Exception {
        // architect PR #197 R2: 같은 입력 스냅샷으로 계산이 실패한 날은 5분마다 일봉 전체를 다시 읽지 않는다
        Path root = tmp.resolve("r2");
        Path sessions = root.resolve("live-sessions");
        Path snapshots = SelectionFixtures.dailySnapshot(root.resolve("snapshots"), "krx-daily-live-0001", D1.minusDays(1),
                false);
        // 스냅샷 ID를 고정하면 D2(기준 데이터일 10-14)는 읽은 뒤에 STALE_INPUT이다(입력 키가 사유 파일에 남음)
        LivePlan fixed = LivePlan.parse(yaml(true).replace("daily: \"latest\"", "daily: \"krx-daily-live-0001\""), "x");
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.LongPredicate counting = rows -> {
            loads.incrementAndGet();
            return true;
        };
        LiveSelectionPrep.Report first = LiveSelectionPrep.prepare(fixed, REPO, sessions, snapshots, D2, PRODUCER,
                RunGate.NONE, counting, m -> { });
        assertThat(loads).hasValue(1);
        assertThat(first.outcomes()).singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT));
        SessionSelections files = LiveSetup.of(fixed, REPO).selectionFiles(sessions.resolve(ID));
        assertThat(files.readProblem(D2, hot.selectionHash())).hasValueSatisfying(p -> assertThat(p.inputs())
                .isEqualTo("daily=krx-daily-live-0001;financials="));

        LiveSelectionPrep.Report again = LiveSelectionPrep.prepare(fixed, REPO, sessions, snapshots, D2, PRODUCER,
                RunGate.NONE, counting, m -> { });
        assertThat(loads).hasValue(1); // 읽지 않음
        assertThat(again.ready()).isFalse();
        assertThat(again.note()).contains("같은 입력 스냅샷으로 이미 실패함");
        assertThat(again.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(SelectionPreparer.Status.PROBLEM);
            assertThat(o.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
        });

        // 사유가 다른 입력으로 남은 것이면(새 스냅샷) 다시 읽는다
        files.writeProblem(D2, hot.selectionHash(), SelectionInvalidReason.STALE_INPUT, "앞 스냅샷", "daily=old;financials=");
        LiveSelectionPrep.prepare(fixed, REPO, sessions, snapshots, D2, PRODUCER, RunGate.NONE, counting, m -> { });
        assertThat(loads).hasValue(2);
        // 적용 기록에는 입력 키가 들어가지 않는다(사유와 설명만)
        assertThat(files.apply(D2).item(hot.selectionHash()).reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
        assertThat(Files.readString(files.appliedFile(D2))).doesNotContain("krx-daily-live-0001");
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
    void sessionDoesNotOpenWhenAppliedRecordOfRecordedDayIsMissing() throws Exception {
        // architect PR #197 N1: 줄이 있는 날의 적용 기록을 지우면 그날을 MISSING으로 되살리지 않고 열지 않는다
        Path sessions = tmp.resolve("live-sessions");
        SessionSelections files = prepareDayOne(sessions);
        List<MarketEvent> events = LiveSessionTest.twoDays();
        try (LiveSession s = LiveSession.open(plan(true), REPO, sessions, clock, m -> { })) {
            for (int n = 0; n < 50; n++) {
                clock.now = events.get(n).receivedAt();
                s.accept("KIWOOM-WS", events.get(n));
            }
        }
        Files.delete(files.appliedFile(D1));
        assertThatThrownBy(() -> LiveSession.open(plan(true), REPO, sessions, clock, m -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("적용 기록").hasMessageContaining(D1.toString());
        LiveSetup setup = LiveSetup.of(plan(true), REPO);
        assertThatThrownBy(() -> LiveReplayCheck.requireApplied(setup, sessions.resolve(ID), List.of(D1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("적용 기록");
        // 줄이 없는 날은 적용 기록이 없어도 된다(그날 첫 줄 전에 죽었거나 아직 오지 않은 날)
        LiveReplayCheck.requireApplied(setup, sessions.resolve(ID), List.of());
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

    /** 선정을 (라) {@code kr-intraday-news@1}로 바꾼 등록(공시 입력 {@code disclosures}). */
    private static String newsYaml(String disclosures) {
        return yaml(true).replace("kr-intraday-hot@1", "kr-intraday-news@1")
                .replace("  daily: \"latest\"\n", "  daily: \"latest\"\n  disclosures: \"" + disclosures + "\"\n");
    }

    @Test
    void disclosureSelectionPreparedFromLatestDisclosureSnapshotAndReplayRecomputesIt() throws Exception {
        // ADR-014 F9: 실시간 공시 latest = 마지막 수집 시각이 가장 늦은 공시 스냅샷. 결과 파일에 그 ID가 남고 세션·리플레이 확인은
        // 그 파일을 읽는다. 공시가 T 전날까지를 담지 않은 날(D2)은 STALE_INPUT이다(일봉은 D2 기준 데이터일 D1까지 담음)
        Path root = tmp.resolve("news");
        Path dir = root.resolve("experiments").resolve(ID);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("experiment.yaml"), newsYaml("latest"), StandardCharsets.UTF_8);
        LivePlan plan = LivePlan.parse(newsYaml("latest"), "experiment.yaml");
        SelectionProfile news = SelectionFixtures.intradayNews(REPO);
        Path sessions = root.resolve("live-sessions");
        Path snapshots = SelectionFixtures.dailySnapshot(root.resolve("snapshots"), "krx-daily-live-0001", D1, false);
        LocalDate c1 = D1.minusDays(1);
        // 앞 수집(000660 공시)과 뒤 수집(005930 공시): 뒤 것을 고른다
        SelectionFixtures.disclosureSnapshot(snapshots, "dart-disclosures-live-0002", LocalDate.of(2026, 1, 1), c1, c1,
                List.of("000660"), "2026-10-13T20:00:00Z");
        SelectionFixtures.disclosureSnapshot(snapshots, "dart-disclosures-live-0001", LocalDate.of(2026, 1, 1), c1, c1,
                List.of("005930"), "2026-10-13T22:00:00Z");

        LiveSelectionPrep.Report d1 = LiveSelectionPrep.prepare(plan, REPO, sessions, snapshots, D1, PRODUCER, RunGate.NONE,
                rows -> true, m -> { });
        assertThat(d1.ready()).isTrue();
        assertThat(d1.disclosuresSnapshot()).isEqualTo("dart-disclosures-live-0001");
        SessionSelections files = LiveSetup.of(plan, REPO).selectionFiles(sessions.resolve(ID));
        assertThat(Files.readString(files.resultFile(D1, news.selectionHash()))).contains("\"disclosures\":{")
                .contains("dart-disclosures-live-0001");

        LiveSelectionPrep.Report d2 = LiveSelectionPrep.prepare(plan, REPO, sessions, snapshots, D2, PRODUCER, RunGate.NONE,
                rows -> true, m -> { });
        assertThat(d2.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
            assertThat(o.detail()).contains("공시 스냅샷이 gate.01");
        });
        // 사유 파일 입력 키에 공시 스냅샷이 들어가 같은 입력이면 다시 읽지 않고, 새 공시 스냅샷이 나오면 다시 계산한다
        assertThat(files.readProblem(D2, news.selectionHash())).hasValueSatisfying(p -> assertThat(p.inputs())
                .isEqualTo("daily=krx-daily-live-0001;financials=;disclosures=dart-disclosures-live-0001"));
        assertThat(LiveSelectionPrep.prepare(plan, REPO, sessions, snapshots, D2, PRODUCER, RunGate.NONE, rows -> true,
                m -> { }).note()).contains("같은 입력 스냅샷으로 이미 실패함");

        LiveSession s = LiveSession.open(plan, REPO, sessions, clock, m -> { });
        for (MarketEvent e : LiveSessionTest.twoDays()) {
            clock.now = e.receivedAt();
            s.accept("KIWOOM-WS", e);
        }
        assertThat(s.appliedSelections(D1)).hasValueSatisfying(a -> assertThat(a.item(news.selectionHash())
                .candidates().candidates()).containsExactly("005930"));
        assertThat(s.appliedSelections(D2)).hasValueSatisfying(a -> assertThat(a.item(news.selectionHash()).reason())
                .isEqualTo(SelectionInvalidReason.STALE_INPUT));
        SimulationReport live = s.finish(new CodeVersion("live", false));
        Files.writeString(dir.resolve(LiveSession.LIVE_DETAIL_FILE), ReportWriter.render(live), StandardCharsets.UTF_8);
        LiveReplayCheck.Outcome out = LiveReplayCheck.run(dir, REPO, sessions, snapshots, new CodeVersion("check", true),
                m -> { }, RunGate.NONE);
        assertThat(out.same()).isTrue();
        assertThat(out.files().get("inputs.yaml")).contains("selection-recompute: \"같음 1, 다름 0, 재계산 생략 0\"");
        // 결과 파일에 적힌 공시 스냅샷이 PC에 없으면 재계산을 생략한다(다름이 아님)
        Path moved = snapshots.resolve("dart-disclosures").resolve("dart-disclosures-live-0001");
        Files.move(moved, moved.resolveSibling("gone"));
        assertThat(LiveReplayCheck.run(dir, REPO, sessions, snapshots, new CodeVersion("check", true), m -> { },
                RunGate.NONE).files().get("inputs.yaml")).contains("재계산 생략 1");
    }

    @Test
    void missingDisclosureSnapshotStopsOnlyTheDisclosureSelection() throws Exception {
        // architect PR #206 R1: 공시 스냅샷이 없으면 공시 조건 선정 (라)만 STALE_INPUT이고, 같은 등록의 (다)는 그대로 만든다.
        // 그 뒤 공시 스냅샷이 생기면 입력 키가 달라져 (라)를 다시 계산한다
        String both = newsYaml("latest").replace("  - \"kr-intraday-news@1 x kr-random-control@1\"\n",
                "  - \"kr-intraday-news@1 x kr-random-control@1\"\n  - \"kr-intraday-hot@1 x kr-random-control@1\"\n");
        LivePlan plan = LivePlan.parse(both, "x");
        SelectionProfile news = SelectionFixtures.intradayNews(REPO);
        Path sessions = tmp.resolve("live-sessions");
        Path snapshots = SelectionFixtures.dailySnapshot(tmp.resolve("snapshots"), "krx-daily-live-0001", D1, false);
        LiveSelectionPrep.Report r = LiveSelectionPrep.prepare(plan, REPO, sessions, snapshots, D1, PRODUCER, RunGate.NONE,
                rows -> true, m -> { });
        assertThat(r.disclosuresSnapshot()).isEmpty();
        assertThat(r.outcomes()).hasSize(2);
        assertThat(r.outcomes()).filteredOn(o -> o.selection().equals(news.key())).singleElement().satisfies(o -> {
            assertThat(o.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
            assertThat(o.detail()).contains("공시 입력이 없습니다");
        });
        assertThat(r.outcomes()).filteredOn(o -> o.selection().equals(hot.key())).singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(SelectionPreparer.Status.WRITTEN));

        SelectionFixtures.disclosureSnapshot(snapshots, "dart-disclosures-live-0001", LocalDate.of(2026, 1, 1),
                D1.minusDays(1), D1.minusDays(1), List.of("005930"), "2026-10-13T22:00:00Z");
        LiveSelectionPrep.Report again = LiveSelectionPrep.prepare(plan, REPO, sessions, snapshots, D1, PRODUCER,
                RunGate.NONE, rows -> true, m -> { });
        assertThat(again.ready()).isTrue();
        assertThat(again.disclosuresSnapshot()).isEqualTo("dart-disclosures-live-0001");
        assertThat(again.outcomes()).filteredOn(o -> o.selection().equals(news.key())).singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(SelectionPreparer.Status.WRITTEN));
    }

    @Test
    void disclosureInputMustMatchTheSelections() {
        // 공시 조건 선정 (라)인데 공시 입력이 없음 → 거절. 공시 조건이 없는 (다)에 공시 입력 → 거절(ADR-014 6장)
        String newsWithout = yaml(true).replace("kr-intraday-hot@1", "kr-intraday-news@1");
        assertThatThrownBy(() -> LiveSetup.of(LivePlan.parse(newsWithout, "x"), REPO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input.disclosures");
        String hotWith = yaml(true).replace("  daily: \"latest\"\n", "  daily: \"latest\"\n  disclosures: \"latest\"\n");
        assertThatThrownBy(() -> LiveSetup.of(LivePlan.parse(hotWith, "x"), REPO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input.disclosures가 있습니다");
        // 실시간은 latest와 스냅샷 ID를 모두 받는다
        assertThat(LiveSetup.of(LivePlan.parse(newsYaml("latest"), "x"), REPO).hasSelections()).isTrue();
        assertThat(LivePlan.parse(newsYaml("dart-disclosures-x-0001"), "x").selections().disclosures())
                .hasValue("dart-disclosures-x-0001");
        assertThat(plan(true).selections().disclosures()).isEmpty();
        // 공시 입력 없는 사유 파일 입력 키는 F9 전과 같다(이미 남은 사유 파일을 다른 입력으로 보지 않음)
        assertThat(SelectionPreparer.inputsKey("d", java.util.Optional.of("f"), java.util.Optional.empty()))
                .isEqualTo(SelectionPreparer.inputsKey("d", java.util.Optional.of("f")));
    }

    @Test
    void registrationRecordingRejectsLatest() {
        String noInput = yaml(true).replace("selection-input:\n  daily: \"latest\"\n", "");
        // 장전 선정이 있는데 일봉 입력이 없으면 거절한다. 선정 프로필을 읽어야 장전·장중을 알아서 설정을 만들 때 본다(D-127)
        assertThatThrownBy(() -> LiveSetup.of(LivePlan.parse(noInput, "x"), REPO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selection-input.daily");
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
