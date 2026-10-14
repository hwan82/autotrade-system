package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.SimulationReport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실시간 모의 세션(ADR-010, D-076, SIM-13): 입력 기록을 먼저 쓰고 그 줄을 계좌에 주므로, 실험실 재기동과 끊김 공백이 있어도
 * 세션 결과가 입력 기록 리플레이와 바이트로 같다.
 */
class LiveSessionTest {

    private static final Path REPO = Path.of("..");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String ID = "EXP-TEST-LIVE01";
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");

    @TempDir
    Path tmp;

    Path experimentDir;
    Path sessions;
    LivePlan plan;
    final Mutable clock = new Mutable();

    static final class Mutable extends Clock {
        Instant now = Instant.parse("2026-10-14T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void register() throws Exception {
        experimentDir = tmp.resolve("experiments").resolve(ID);
        Files.createDirectories(experimentDir);
        String yaml = String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"실시간 세션이 리플레이와 같은 결과를 내는가\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-15\"",
                "seed: \"20261014\"",
                "seed-capital: \"10000000\"",
                "benchmark: \"005930\"",
                "profiles:",
                "  - \"kr-bh-baseline@1\"",
                "  - \"kr-orb-30@1\"",
                "  - \"kr-random-control@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "  - \"000660 KOSPI\"",
                "");
        Files.writeString(experimentDir.resolve("experiment.yaml"), yaml, StandardCharsets.UTF_8);
        plan = LivePlan.parse(yaml, "experiment.yaml");
        sessions = tmp.resolve("live-sessions");
    }

    /** 이틀치 시세: 두 종목 체결·호가가 섞인다. 같은 난수 씨앗이라 늘 같다. */
    static List<MarketEvent> twoDays() {
        Random r = new Random(7);
        List<MarketEvent> out = new ArrayList<>();
        long[] px = {60000, 180000};
        long[] tick = {100, 500};
        long seq = 0;
        for (LocalDate d : List.of(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15))) {
            Instant t = d.atTime(9, 0, 1).atZone(SEOUL).toInstant();
            Instant end = d.atTime(15, 19, 0).atZone(SEOUL).toInstant();
            while (t.isBefore(end)) {
                int i = r.nextInt(2);
                px[i] += (r.nextInt(3) - 1) * tick[i];
                Symbol s = i == 0 ? SAMSUNG : HYNIX;
                BigDecimal p = BigDecimal.valueOf(px[i]);
                seq++;
                if (r.nextInt(3) == 0) {
                    out.add(new OrderBookSnapshot(s, seq, t, t.plusMillis(20),
                            List.of(new PriceLevel(p.add(BigDecimal.valueOf(tick[i])), 100 + r.nextInt(900))),
                            List.of(new PriceLevel(p, 100 + r.nextInt(900)))));
                } else {
                    out.add(new TradeTick(s, seq, t, t.plusMillis(20), p, 1 + r.nextInt(50)));
                }
                t = t.plusMillis(5_000 + r.nextInt(20_000));
            }
        }
        return out;
    }

    private LiveSession open() {
        return LiveSession.open(plan, REPO, sessions, clock, m -> { });
    }

    private void writeLiveDetail(SimulationReport report) throws Exception {
        Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), ReportWriter.render(report),
                StandardCharsets.UTF_8);
    }

    @Test
    void liveResultEqualsJournalReplayAcrossLabRestartAndFeedGap() throws Exception {
        List<MarketEvent> events = twoDays();
        int restartAt = events.size() * 2 / 3;
        LiveSession s = open();
        for (int n = 0; n < events.size(); n++) {
            MarketEvent e = events.get(n);
            clock.now = e.receivedAt();
            if (n == restartAt) {
                s.close(); // 실험실 재기동: 기록으로 계좌를 되살리고, 끊긴 구간은 공백으로 남는다
                s = open();
                assertThat(s.status().restored()).isEqualTo(s.status().events()).isPositive();
                s.accept("FEED-CLIENT", new DataGap(SAMSUNG, 0, e.receivedAt(), e.receivedAt(),
                        e.receivedAt().minusSeconds(30), e.receivedAt(), DataGap.GapReason.DISCONNECTED));
            }
            assertThat(s.accept("KIWOOM-WS", e)).isEqualTo(LiveSession.Accepted.ACCEPTED);
        }
        LiveSession.Status status = s.status();
        assertThat(status.runs()).hasSize(3);
        assertThat(status.failures()).allSatisfy(f -> assertThat(f).isEmpty());
        assertThat(status.dataGaps()).isGreaterThanOrEqualTo(3); // 재기동 공백 2종목 + 끊김 공백 1
        assertThat(status.events()).isEqualTo(events.size() + status.dataGaps());

        SimulationReport live = s.finish(new CodeVersion("live-commit", false));
        assertThat(live.result().runs()).allSatisfy(r -> assertThat(r.failureReason()).isEmpty());
        writeLiveDetail(live);

        LiveReplayCheck.Outcome out = LiveReplayCheck.run(experimentDir, REPO, sessions,
                new CodeVersion("check-commit", true), m -> { }, RunGate.NONE);
        assertThat(out.same()).isTrue();
        assertThat(out.files()).containsOnlyKeys("inputs.yaml", "results.md", "detail.txt");
        assertThat(out.files().get("inputs.yaml")).startsWith("# 실시간 모의(D-076)").contains("clock-mode: REALTIME");
        assertThat(LiveReplayCheck.withoutCodeLine(out.files().get("detail.txt")))
                .isEqualTo(LiveReplayCheck.withoutCodeLine(ReportWriter.render(live)));
    }

    @Test
    void crashWithLostTailAndUnclosedMetaStillReplaysTheSame() throws Exception {
        List<MarketEvent> events = twoDays();
        int crashAt = events.size() / 3;
        crashAndResume(events, crashAt, crashAt);
    }

    @Test
    void crashOnDayOneThenResumingOnDayTwoStillReplaysTheSame() throws Exception {
        // 1일차 중간·끝에서 비정상 종료한 뒤 2일차 이벤트로 다시 시작해도(1일차 파일은 다시 열리지 않음) 같다(QA PR #87 R5)
        List<MarketEvent> events = twoDays();
        LocalDate dayOne = LocalDate.of(2026, 10, 14);
        int dayTwoAt = (int) events.stream().filter(e -> e.receivedAt().atZone(SEOUL).toLocalDate().equals(dayOne))
                .count();
        assertThat(dayTwoAt).isPositive().isLessThan(events.size());
        crashAndResume(events, dayTwoAt / 2, dayTwoAt);
        crashAndResume(events, dayTwoAt, dayTwoAt);
    }

    /** {@code crashAt}개를 받은 뒤 닫지 않고 꼬리를 잘라 남기고, {@code resumeFrom}번째부터 이어 받아 리플레이와 같은지 본다. */
    private void crashAndResume(List<MarketEvent> events, int crashAt, int resumeFrom) throws Exception {
        LiveSession crashed = open();
        for (MarketEvent e : events.subList(0, crashAt)) {
            clock.now = e.receivedAt();
            crashed.accept("KIWOOM-WS", e);
        }
        crashed.flush();
        // 비정상 종료: 닫지 않은 채(메타 미종료) 세션 폴더만 남고, 디스크에 다 내리지 못한 꼬리가 잘린다
        Path copy = Files.createTempDirectory(tmp, "crashed-sessions");
        Path from = sessions.resolve(ID);
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path to = copy.resolve(ID).resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
        }
        crashed.close();
        deleteTree(from);
        Path day = copy.resolve(ID).resolve(LiveSession.JOURNAL_DIR).resolve("KRX").resolve("2026-10-14")
                .resolve("events.jsonl.gz");
        byte[] bytes = Files.readAllBytes(day);
        Files.write(day, java.util.Arrays.copyOf(bytes, bytes.length - 20));

        LiveSession resumed = LiveSession.open(plan, REPO, copy, clock, m -> { });
        assertThat(resumed.status().restored()).isPositive().isLessThanOrEqualTo(crashAt);
        for (MarketEvent e : events.subList(resumeFrom, events.size())) {
            clock.now = e.receivedAt();
            assertThat(resumed.accept("KIWOOM-WS", e)).isEqualTo(LiveSession.Accepted.ACCEPTED);
        }
        writeLiveDetail(resumed.finish(new CodeVersion("c", false)));

        LiveReplayCheck.Outcome out = LiveReplayCheck.run(experimentDir, REPO, copy, new CodeVersion("c", false),
                m -> { }, RunGate.NONE);
        assertThat(out.same()).isTrue();
    }

    private static void deleteTree(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void pricesOffTheStockTickStopTheSessionWithoutWritingThem() {
        try (LiveSession s = open()) {
            Instant t = LocalDate.of(2026, 10, 14).atTime(10, 0).atZone(SEOUL).toInstant();
            clock.now = t;
            // ETF처럼 5원 단위 가격(주식 60,000원대 호가 단위는 100원)
            assertThat(s.accept("KIWOOM-WS", new TradeTick(SAMSUNG, 1, t, t, new BigDecimal("60005"), 1)))
                    .isEqualTo(LiveSession.Accepted.STOPPED);
            assertThat(s.status().stopReason()).contains("호가 단위").contains("005930");
            assertThat(s.status().events()).isZero();
            assertThat(s.journalDays()).isEmpty();
            assertThat(s.accept("KIWOOM-WS", new TradeTick(SAMSUNG, 2, t, t, new BigDecimal("60000"), 1)))
                    .isEqualTo(LiveSession.Accepted.STOPPED);
        }
    }

    @Test
    void blacklistedSymbolIsNeverBoughtInTheLiveSession() throws Exception {
        String yaml = Files.readString(experimentDir.resolve("experiment.yaml"), StandardCharsets.UTF_8)
                + "blacklist:\n  - \"000660\"\n";
        LivePlan black = LivePlan.parse(yaml, "experiment.yaml");
        List<MarketEvent> day = twoDays().stream()
                .filter(e -> e.receivedAt().atZone(SEOUL).toLocalDate().equals(LocalDate.of(2026, 10, 14))).toList();

        SimulationReport withBlacklist = runDay(black, tmp.resolve("black"), day);
        SimulationReport without = runDay(plan, tmp.resolve("plain"), day);

        // 같은 시세에서 블랙리스트가 없으면 매수 후보(buy-and-hold, A01)가 하이닉스를 사므로 대조가 된다
        assertThat(without.runs().get(0).fills()).anySatisfy(f -> assertThat(f.fill().symbol()).isEqualTo(HYNIX));
        assertThat(withBlacklist.runs()).allSatisfy(r -> assertThat(r.fills())
                .noneSatisfy(f -> assertThat(f.fill().symbol()).isEqualTo(HYNIX)));
        assertThat(withBlacklist.runs()).flatExtracting(r -> r.rejections()).isNotEmpty()
                .allSatisfy(x -> assertThat(x.symbol()).isEqualTo(HYNIX));
    }

    private SimulationReport runDay(LivePlan p, Path root, List<MarketEvent> events) {
        try (LiveSession s = LiveSession.open(p, REPO, root, clock, m -> { })) {
            for (MarketEvent e : events) {
                clock.now = e.receivedAt();
                s.accept("KIWOOM-WS", e);
            }
            return s.finish(new CodeVersion("c", false));
        }
    }

    @Test
    void replayCheckDetectsADifferentLiveReport() throws Exception {
        LiveSession s = open();
        for (MarketEvent e : twoDays().subList(0, 300)) {
            clock.now = e.receivedAt();
            s.accept("KIWOOM-WS", e);
        }
        String detail = ReportWriter.render(s.finish(new CodeVersion("c", false)));
        String tampered = detail.replaceFirst("\n([^\n]*[0-9])", "\n$1 9");
        assertThat(tampered).isNotEqualTo(detail);
        Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), tampered, StandardCharsets.UTF_8);

        LiveReplayCheck.Outcome out = LiveReplayCheck.run(experimentDir, REPO, sessions, new CodeVersion("c", false),
                m -> { }, RunGate.NONE);
        assertThat(out.same()).isFalse();
        assertThat(out.files().get("results.md")).contains("다름");
    }

    @Test
    void skipsSymbolsOutsideBoardsAndEventsOutsideThePeriod() {
        try (LiveSession s = open()) {
            Instant t = LocalDate.of(2026, 10, 14).atTime(10, 0).atZone(SEOUL).toInstant();
            assertThat(s.accept("KIWOOM-WS", new TradeTick(Symbol.krx("035420"), 1, t, t, new BigDecimal("200000"), 1)))
                    .isEqualTo(LiveSession.Accepted.SKIPPED_SYMBOL);
            Instant before = LocalDate.of(2026, 10, 13).atTime(10, 0).atZone(SEOUL).toInstant();
            assertThat(s.accept("KIWOOM-WS", new TradeTick(SAMSUNG, 2, before, before, new BigDecimal("60000"), 1)))
                    .isEqualTo(LiveSession.Accepted.SKIPPED_RANGE);
            Instant after = LocalDate.of(2026, 10, 16).atTime(10, 0).atZone(SEOUL).toInstant();
            assertThat(s.accept("KIWOOM-WS", new TradeTick(SAMSUNG, 3, after, after, new BigDecimal("60000"), 1)))
                    .isEqualTo(LiveSession.Accepted.SKIPPED_RANGE);
            LiveSession.Status status = s.status();
            assertThat(status.events()).isZero();
            assertThat(status.skippedSymbol()).isEqualTo(1);
            assertThat(status.skippedRange()).isEqualTo(2);
            assertThat(s.journalDays()).isEmpty();
        }
    }

    @Test
    void sessionIsLockedWhileOpenAndReplayCheckRefusesIt() throws Exception {
        try (LiveSession s = open()) {
            Instant t = LocalDate.of(2026, 10, 14).atTime(10, 0).atZone(SEOUL).toInstant();
            clock.now = t;
            s.accept("KIWOOM-WS", new TradeTick(SAMSUNG, 1, t, t, new BigDecimal("60000"), 1));
            assertThatThrownBy(this::open).isInstanceOf(IllegalStateException.class);
            Files.writeString(experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE), "x", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> LiveReplayCheck.run(experimentDir, REPO, sessions, new CodeVersion("c", false),
                    m -> { }, RunGate.NONE)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("열고 있습니다");
        }
        open().close(); // 닫으면 다시 열 수 있다
    }

    @Test
    void finishingWithoutEventsGivesAnEmptyReportAndStopsAccepting() {
        LiveSession s = open();
        SimulationReport report = s.finish(new CodeVersion("c", false));
        assertThat(report.result().runs()).hasSize(3);
        Instant t = LocalDate.of(2026, 10, 14).atTime(10, 0).atZone(SEOUL).toInstant();
        assertThat(s.accept("KIWOOM-WS", new TradeTick(SAMSUNG, 1, t, t, new BigDecimal("60000"), 1)))
                .isEqualTo(LiveSession.Accepted.STOPPED);
        s.close();
    }

    @Test
    void replayCheckNeedsTheLiveReport() {
        assertThatThrownBy(() -> LiveReplayCheck.run(experimentDir, REPO, sessions, new CodeVersion("c", false),
                m -> { }, RunGate.NONE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LiveSession.LIVE_DETAIL_FILE);
    }
}
