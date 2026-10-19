package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.recording.JournalWriter;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 6단계 주간 점검(D-088): 입력 기록과 같은 날 게이트웨이 녹화를 대조해, 실험실이 받지 못한 이벤트를 원인(세션 밖, 끊김, 재기동,
 * 큐 넘침)별로 손 계산과 같은 수로 세고, 공백 없이 빠지거나 녹화에 없는 줄이 생기면 결함으로 낸다.
 */
class LiveWeeklyCheckTest {

    private static final Path REPO = Path.of("..");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String ID = "EXP-TEST-LIVE01";
    private static final String SRC = "KIWOOM-WS";
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");
    private static final Symbol KAKAO = Symbol.krx("035720");
    private static final LocalDate DAY1 = LocalDate.of(2026, 10, 14);
    private static final LocalDate DAY2 = LocalDate.of(2026, 10, 15);

    @TempDir
    Path tmp;

    Path experimentDir;
    Path sessions;
    Path recordings;
    Path snapshots;
    LivePlan plan;
    final LiveSessionTest.Mutable clock = new LiveSessionTest.Mutable();

    @BeforeEach
    void register() throws Exception {
        experimentDir = tmp.resolve("experiments").resolve(ID);
        Files.createDirectories(experimentDir);
        String yaml = String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"실시간 세션이 녹화와 같은 입력을 받는가\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-16\"",
                "seed: \"20261014\"",
                "seed-capital: \"10000000\"",
                "benchmark: \"005930\"",
                "profiles:",
                "  - \"kr-bh-baseline@1\"",
                "  - \"kr-random-control@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "  - \"000660 KOSPI\"",
                "");
        Files.writeString(experimentDir.resolve("experiment.yaml"), yaml, StandardCharsets.UTF_8);
        plan = LivePlan.parse(yaml, "experiment.yaml");
        sessions = tmp.resolve("live-sessions");
        recordings = tmp.resolve("recordings");
        snapshots = tmp.resolve("snapshots");
    }

    /** 게이트웨이 녹화: 이틀치 시세에 boards 밖 종목(카카오)이 섞인다. 녹화기와 같은 줄 형식. */
    private List<MarketEvent> recordGateway(List<LocalDate> days) {
        return recordGateway(days, -1);
    }

    /** {@code twiceAt}번째 이벤트는 같은 줄로 두 번 녹화한다(음수면 없음). */
    private List<MarketEvent> recordGateway(List<LocalDate> days, int twiceAt) {
        List<MarketEvent> events = LiveSessionTest.twoDays().stream()
                .filter(e -> days.contains(e.receivedAt().atZone(SEOUL).toLocalDate())).toList();
        JournalWriter w = new JournalWriter(new RecordingLayout(recordings), Market.KRX, SEOUL, Map.of(SRC, "테스트 녹화"),
                "테스트");
        long seq = 1_000_000;
        for (int i = 0; i < events.size(); i++) {
            MarketEvent e = events.get(i);
            w.append(SRC, e, e.receivedAt());
            if (i == twiceAt) {
                w.append(SRC, e, e.receivedAt());
            }
            if (i % 50 == 0) {
                w.append(SRC, new TradeTick(KAKAO, ++seq, e.exchangeTime(), e.receivedAt(), new BigDecimal("40000"), 1),
                        e.receivedAt());
            }
        }
        w.close(events.get(events.size() - 1).receivedAt());
        return events;
    }

    private LiveSession open() {
        return LiveSession.open(plan, REPO, sessions, clock, m -> { });
    }

    private LiveWeeklyCheck.Outcome check(Optional<LocalDate> to) {
        return LiveWeeklyCheck.run(experimentDir, REPO, sessions, recordings, snapshots, to, new CodeVersion("c", false), m -> { },
                RunGate.NONE);
    }

    private static int indexOfDay(List<MarketEvent> events, LocalDate day) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).receivedAt().atZone(SEOUL).toLocalDate().equals(day)) {
                return i;
            }
        }
        throw new AssertionError(day);
    }

    @Test
    void cleanSessionMatchesTheRecordingLineForLineAndAccountsAreTheSame() throws Exception {
        List<MarketEvent> events = recordGateway(List.of(DAY1, DAY2));
        try (LiveSession s = open()) {
            for (MarketEvent e : events) {
                clock.now = e.receivedAt();
                s.accept(SRC, e);
            }
        }

        LiveWeeklyCheck.Outcome out = check(Optional.empty());

        assertThat(out.defect()).isFalse();
        assertThat(out.accountsCompared()).isTrue();
        assertThat(out.from()).isEqualTo(DAY1);
        assertThat(out.to()).isEqualTo(DAY2);
        assertThat(out.days()).hasSize(2).allSatisfy(r -> {
            assertThat(r.recordedEvents()).isEqualTo(r.matched()).isEqualTo(r.journalLines()).isPositive();
            assertThat(r.missingTotal()).isZero();
            assertThat(r.extraInRecorderGap() + r.extraUnexplained()).isZero();
        });
        assertThat(out.days().stream().mapToLong(LiveWeeklyCheck.DayRow::matched).sum()).isEqualTo(events.size());
        // 빠진 이벤트가 없으면 녹화 리플레이와 입력 기록 리플레이가 체결 원장까지 같다
        assertThat(out.report()).contains("상세 보고서: 같음").contains("설명되지 않는 차이 없음")
                .contains("| kr-bh-baseline@1 |").doesNotContain("035720");
        // 수집 스냅샷이 없으면 4장과 머리에 확인 필요로 나오지만, 전달·기록 결함(종료 코드 2)과는 따로다(D-105)
        assertThat(out.collection().kind()).isEqualTo(CollectionFreshness.Kind.NO_SNAPSHOT);
        assertThat(out.report()).contains("- 수집: **확인 필요**: 일봉 스냅샷이 없음").contains("## 4. 수집 멈춤 여부(D-105)");

        // 점검 마지막 날(DAY2) 하루 전까지 수집됐으면 정상, 그보다 이틀 이상 뒤처지면 멈춤 의심
        CollectionFreshnessTest.writeSnapshot(snapshots, "krx-daily-2026-10-14-0000aaaa", DAY1);
        assertThat(check(Optional.empty()).collection().kind()).isEqualTo(CollectionFreshness.Kind.OK);
        Files.walk(snapshots).sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        CollectionFreshnessTest.writeSnapshot(snapshots, "krx-daily-2026-10-13-0000bbbb", LocalDate.of(2026, 10, 13));
        LiveWeeklyCheck.Outcome behind = check(Optional.empty());
        assertThat(behind.defect()).isFalse();
        assertThat(behind.collection().behind()).containsExactly(DAY1, DAY2);
        assertThat(behind.report()).contains("- 수집: **멈춤 의심**: 일봉 스냅샷 마지막 거래일 2026-10-13, 그 뒤 2거래일 수집 안 됨")
                .contains("- 수집 안 된 거래일: 2026-10-14, 2026-10-15");
    }

    @Test
    void missingEventsAreCountedByTheLabGapThatCoversThem() {
        List<MarketEvent> events = recordGateway(List.of(DAY1, DAY2));
        int day2 = indexOfDay(events, DAY2);
        Map<String, Long> expected = new LinkedHashMap<>();
        LiveSession s = open();
        int i = 5; // 1일차 처음 5개는 세션 시작 전
        expected.merge(LiveWeeklyCheck.OUTSIDE_SESSION, 5L, Long::sum);
        int inboxFrom = 100;
        int disconnectFrom = 200;
        int restartAt = day2 + 100;
        int endAt = events.size() - 3; // 2일차 마지막 3개는 세션을 끝낸 뒤
        expected.merge(LiveWeeklyCheck.OUTSIDE_SESSION, 3L, Long::sum);
        Instant overflowSince = null;
        int feedFrom = 300;
        Instant feedSince = null;
        while (i < endAt) {
            MarketEvent e = events.get(i);
            if (i >= feedFrom && i < feedFrom + 20 && e.symbol().equals(HYNIX)) {
                // 게이트웨이 전달 큐 넘침: SK하이닉스만 버리고, 다음 SK하이닉스 이벤트 앞에 게이트웨이가 그 종목 공백
                feedSince = feedSince == null ? e.receivedAt() : feedSince;
                expected.merge(FeedServer.SOURCE_ID, 1L, Long::sum);
                i++;
                continue;
            }
            if (feedSince != null && e.symbol().equals(HYNIX)) {
                clock.now = e.receivedAt();
                s.accept(FeedServer.SOURCE_ID, new DataGap(HYNIX, 0, e.receivedAt(), e.receivedAt(), feedSince,
                        e.receivedAt(), DataGap.GapReason.CONSUMER_OVERFLOW));
                feedSince = null;
            }
            if (i >= inboxFrom && i < inboxFrom + 20 && e.symbol().equals(SAMSUNG)) {
                // 실험실 받는 큐 넘침: 삼성전자만 버리고, 다음 삼성전자 이벤트 앞에 그 종목 공백
                overflowSince = overflowSince == null ? e.receivedAt() : overflowSince;
                expected.merge(LiveSession.INBOX_SOURCE, 1L, Long::sum);
                i++;
                continue;
            }
            clock.now = e.receivedAt();
            if (overflowSince != null && e.symbol().equals(SAMSUNG)) {
                s.accept(LiveSession.INBOX_SOURCE, new DataGap(SAMSUNG, 0, e.receivedAt(), e.receivedAt(), overflowSince,
                        e.receivedAt(), DataGap.GapReason.CONSUMER_OVERFLOW));
                overflowSince = null;
            }
            if (i == disconnectFrom) {
                // 받는 쪽 끊김: 10개를 못 받고, 다시 붙은 뒤 본 종목마다 공백
                Instant lastSeen = events.get(i - 1).receivedAt();
                expected.merge(FeedClient.SOURCE_ID, 10L, Long::sum);
                i += 10;
                MarketEvent next = events.get(i);
                clock.now = next.receivedAt();
                for (Symbol sym : List.of(HYNIX, SAMSUNG)) {
                    s.accept(FeedClient.SOURCE_ID, new DataGap(sym, 0, next.receivedAt(), next.receivedAt(), lastSeen,
                            next.receivedAt(), DataGap.GapReason.DISCONNECTED));
                }
                continue;
            }
            if (i == restartAt) {
                // 실험실 재기동: 7개를 못 받고, 기록이 재기동 공백을 남긴다
                clock.now = events.get(i - 1).receivedAt();
                s.close();
                expected.merge(LiveWeeklyCheck.RECORDER_SOURCE, 7L, Long::sum);
                i += 7;
                clock.now = events.get(i).receivedAt();
                s = open();
                continue;
            }
            assertThat(s.accept(SRC, e)).isEqualTo(LiveSession.Accepted.ACCEPTED);
            i++;
        }
        s.close();

        LiveWeeklyCheck.Outcome out = check(Optional.empty());

        Map<String, Long> missing = new LinkedHashMap<>();
        for (LiveWeeklyCheck.DayRow r : out.days()) {
            r.missing().forEach((k, v) -> missing.merge(k, v, Long::sum));
        }
        expected.forEach((k, v) -> assertThat(missing.get(k)).as(k).isEqualTo(v));
        assertThat(missing.get(LiveWeeklyCheck.UNEXPLAINED)).isZero();
        assertThat(out.defect()).isFalse();
        LiveWeeklyCheck.DayRow first = out.days().get(0);
        assertThat(first.reconnects()).isEqualTo(1);
        assertThat(first.labGaps()).containsEntry(FeedClient.SOURCE_ID, 2L).containsEntry(LiveSession.INBOX_SOURCE, 1L)
                .containsEntry(FeedServer.SOURCE_ID, 1L);
        assertThat(out.days().get(1).labGaps().get(LiveWeeklyCheck.RECORDER_SOURCE)).isPositive();
        long dropped = expected.values().stream().mapToLong(Long::longValue).sum();
        assertThat(out.days().stream().mapToLong(LiveWeeklyCheck.DayRow::matched).sum())
                .isEqualTo(events.size() - dropped);
        assertThat(out.accountsCompared()).isTrue();
        assertThat(out.report()).contains("| 운용 시작부터 누계 |").contains("| 2026-10-15 |");
    }

    @Test
    void silentLossAndLinesTheGatewayNeverRecordedAreDefects() {
        List<MarketEvent> events = recordGateway(List.of(DAY1));
        try (LiveSession s = open()) {
            for (int i = 0; i < events.size(); i++) {
                MarketEvent e = events.get(i);
                clock.now = e.receivedAt();
                if (i == 50) {
                    continue; // 공백 없이 빠짐
                }
                if (i == 60) {
                    // 녹화에 없는 줄(내용이 바뀐 전달)
                    TradeTick forged = new TradeTick(e.symbol(), 9_999_999, e.exchangeTime(), e.receivedAt(),
                            new BigDecimal("50000"), 3);
                    s.accept(SRC, forged);
                }
                s.accept(SRC, e);
            }
        }

        LiveWeeklyCheck.Outcome out = check(Optional.empty());

        LiveWeeklyCheck.DayRow r = out.days().get(0);
        assertThat(r.missing()).containsEntry(LiveWeeklyCheck.UNEXPLAINED, 1L);
        assertThat(r.extraUnexplained()).isEqualTo(1);
        assertThat(out.defect()).isTrue();
        assertThat(out.report()).contains("**확인 필요**");
    }

    @Test
    void silentStopWhileTheSessionIsOpenIsADefectNotOutsideTheSession() {
        // 세션이 열린 채 전달이 공백 없이 끊기고(받는 쪽이 끊김을 모름) 장 끝에 닫으면, 그 뒤 녹화 줄은 세션 구간 안이라
        // 설명 없음이다(architect PR #107 R1)
        List<MarketEvent> events = recordGateway(List.of(DAY1));
        int stopAt = events.size() / 2;
        LiveSession s = open();
        for (MarketEvent e : events.subList(0, stopAt)) {
            clock.now = e.receivedAt();
            s.accept(SRC, e);
        }
        clock.now = events.get(events.size() - 1).receivedAt().plusSeconds(60);
        s.close();

        LiveWeeklyCheck.Outcome out = check(Optional.empty());

        LiveWeeklyCheck.DayRow r = out.days().get(0);
        assertThat(r.missing()).containsEntry(LiveWeeklyCheck.UNEXPLAINED, (long) (events.size() - stopAt))
                .containsEntry(LiveWeeklyCheck.OUTSIDE_SESSION, 0L);
        assertThat(out.defect()).isTrue();
    }

    @Test
    void dayTheLabNeverOpenedIsCountedAsMissingAndStillInTheRecordingReplay() {
        // 실험실을 하루 통째로 끈 날(입력 기록 없음, 녹화만 있음)도 행으로 나오고 녹화 줄은 모두 세션 밖 빠짐이다(R2)
        List<MarketEvent> events = recordGateway(List.of(DAY1, DAY2));
        int day2 = indexOfDay(events, DAY2);
        try (LiveSession s = open()) {
            for (MarketEvent e : events.subList(day2, events.size())) {
                clock.now = e.receivedAt();
                s.accept(SRC, e);
            }
        }

        LiveWeeklyCheck.Outcome out = check(Optional.empty());

        assertThat(out.days()).extracting(LiveWeeklyCheck.DayRow::journalPresent).containsExactly(false, true);
        LiveWeeklyCheck.DayRow off = out.days().get(0);
        assertThat(off.recordedEvents()).isEqualTo(day2);
        assertThat(off.missing()).containsEntry(LiveWeeklyCheck.OUTSIDE_SESSION, (long) day2);
        assertThat(off.matched()).isZero();
        assertThat(out.defect()).isFalse();
        assertThat(out.accountsCompared()).isTrue();
        assertThat(out.report()).contains("| 2026-10-14 | 기록 없음 |").contains("점검일 2일 누계, 그중 입력 기록 있는 날 1일");
    }

    @Test
    void dayWithoutRecordingIsShownAndAccountsAreNotCompared() throws Exception {
        recordGateway(List.of(DAY1));
        try (LiveSession s = open()) {
            for (MarketEvent e : LiveSessionTest.twoDays()) {
                clock.now = e.receivedAt();
                s.accept(SRC, e);
            }
        }

        LiveWeeklyCheck.Outcome out = check(Optional.empty());

        assertThat(out.days()).extracting(LiveWeeklyCheck.DayRow::recordingPresent).containsExactly(true, false);
        assertThat(out.accountsCompared()).isFalse();
        assertThat(out.defect()).isFalse();
        assertThat(out.report()).contains("녹화 없음").contains("계좌 대조를 하지 않았습니다: 2026-10-15 녹화 없음");

        // 마지막 날을 1일차로 잡으면 그날까지만 보고 계좌도 비교한다
        LiveWeeklyCheck.Outcome dayOne = check(Optional.of(DAY1));
        assertThat(dayOne.days()).hasSize(1);
        assertThat(dayOne.accountsCompared()).isTrue();
    }

    @Test
    void writesTheReportUnderWeeklyAndRejectsBadInput() throws Exception {
        recordGateway(List.of(DAY1));
        assertThatThrownBy(() -> check(Optional.empty())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("입력 기록이 없습니다");
        try (LiveSession s = open()) {
            for (MarketEvent e : LiveSessionTest.twoDays().subList(0, 200)) {
                clock.now = e.receivedAt();
                s.accept(SRC, e);
            }
            // 세션이 열려 있어도 이미 내린 기록은 읽는다
            s.flush();
            assertThat(check(Optional.of(DAY1)).days()).hasSize(1);
        }
        assertThatThrownBy(() -> check(Optional.of(LocalDate.of(2026, 10, 20))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("운용 기간 밖");

        LiveWeeklyCheck.Outcome out = LiveWeeklyCheck.runAndWrite(experimentDir, REPO, sessions, recordings,
                snapshots, Optional.empty(), new CodeVersion("c", false), m -> { }, RunGate.NONE);
        Path written = experimentDir.resolve(LiveWeeklyCheck.WEEKLY_DIR).resolve("2026-10-14.md");
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).isEqualTo(out.report());
        // 같은 입력이면 같은 보고서(실행 시각 같은 메타가 없음)
        assertThat(check(Optional.empty()).report()).isEqualTo(out.report());
    }

    @Test
    void stopsBeforeReadingWhenTheGateSaysSo() {
        List<MarketEvent> events = recordGateway(List.of(DAY1));
        try (LiveSession s = open()) {
            for (MarketEvent e : events) {
                clock.now = e.receivedAt();
                s.accept(SRC, e);
            }
        }
        AtomicInteger calls = new AtomicInteger();
        RunGate stopOnSecond = () -> calls.incrementAndGet() >= 2 ? Optional.of("정지 요청") : Optional.empty();
        assertThatThrownBy(() -> LiveWeeklyCheck.runAndWrite(experimentDir, REPO, sessions, recordings, snapshots, Optional.empty(),
                new CodeVersion("c", false), m -> { }, stopOnSecond)).isInstanceOf(ExperimentStoppedException.class);
        assertThat(experimentDir.resolve(LiveWeeklyCheck.WEEKLY_DIR)).doesNotExist();
    }

    @Test
    void sameLineSeveralTimesIsMatchedByCountLikeTheOldListComparison() {
        // 같은 내용 줄(같은 열쇠)이 여러 번 나오면 몇 번 나왔는지로 짝 짓는다(D-115 128비트 열쇠). 녹화에 두 번 있고 기록에 한 번
        // 있으면 빠짐 1, 기록에 두 번 있고 녹화에 한 번 있으면 남음 1이다. 예전 목록 대조(문자열 열쇠의 개수 맞추기)와 같은 수다
        List<MarketEvent> events = recordGateway(List.of(DAY1), 10);
        try (LiveSession s = open()) {
            for (int i = 0; i < events.size(); i++) {
                MarketEvent e = events.get(i);
                clock.now = e.receivedAt();
                s.accept(SRC, e);
                if (i == 20) {
                    s.accept(SRC, e);
                }
            }
        }

        LiveWeeklyCheck.Outcome out = check(Optional.empty());

        LiveWeeklyCheck.DayRow r = out.days().get(0);
        assertThat(r.recordedEvents()).isEqualTo(events.size() + 1);
        assertThat(r.journalLines()).isEqualTo(events.size() + 1);
        assertThat(r.matched()).isEqualTo(events.size());
        assertThat(r.missing()).containsEntry(LiveWeeklyCheck.UNEXPLAINED, 1L);
        assertThat(r.missingTotal()).isEqualTo(1);
        assertThat(r.extraUnexplained()).isEqualTo(1);
        assertThat(r.extraInRecorderGap()).isZero();
        assertThat(out.defect()).isTrue();
    }

    @Test
    void recordingChangedAfterTheDayComparisonIsRejectedBeforeAnyEventReachesTheReplay() throws Exception {
        // 하루 대조 때 남긴 파일 지문과 계좌 대조 때 다시 읽는 파일이 다르면, 이벤트를 넘기기 전에 거부한다(D-115)
        recordGateway(List.of(DAY1));
        Path dir = new RecordingLayout(recordings).dayDir(Market.KRX, DAY1);
        LiveSetup setup = LiveSetup.of(plan, REPO);
        LiveWeeklyCheck.BoardsRecording replay = new LiveWeeklyCheck.BoardsRecording(setup);
        LiveWeeklyCheck.DayRow row = LiveWeeklyCheck.compareDay(setup, new RecordingCodec(), DAY1, null, dir, replay);
        assertThat(replay.count()).isEqualTo(row.recordedEvents()).isPositive();

        List<MarketEvent> first = new java.util.ArrayList<>();
        replay.stream("v", RunGate.NONE).forEach(first::add);
        assertThat(first).hasSize((int) replay.count());

        Path file = dir.resolve(RecordingLayout.EVENTS_FILE);
        Files.write(file, new byte[] {0}, java.nio.file.StandardOpenOption.APPEND);
        List<MarketEvent> second = new java.util.ArrayList<>();
        assertThatThrownBy(() -> replay.stream("v", RunGate.NONE).forEach(second::add))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("녹화가 대조한 뒤 바뀌었습니다");
        assertThat(second).isEmpty();
    }
}
