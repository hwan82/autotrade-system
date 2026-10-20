package com.autotrade.app.lab.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.feed.FeedSocket;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.sim.live.LiveSelectionPrep;
import com.autotrade.sim.live.LiveSession;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.selection.SelectionPreparer;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실험실의 실시간 모의 세션 운용(ADR-010 2.4, D-076): 게이트웨이 전달을 받아 세션에 넣고, 감시 정지 요청이면 멈춘다. */
class LiveLabServiceTest {

    private static final String ID = "EXP-TEST-LIVE02";
    private static final Symbol SAMSUNG = Symbol.krx("005930");

    private static final Path REPO = Path.of("..");

    @TempDir
    Path experiments;

    @TempDir
    Path config;

    Path stopRequest;
    /** 디스크 여유(바이트). -1이면 service()에 준 값. */
    final java.util.concurrent.atomic.AtomicLong free = new java.util.concurrent.atomic.AtomicLong(-1);
    LiveLabService service;
    FeedServer gateway;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Files.createDirectories(experiments.resolve(ID));
        Files.writeString(dir.resolve("experiment.yaml"), String.join("\n",
                "id: \"" + ID + "\"",
                "question: \"q\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-14\"",
                "seed: \"1\"",
                "seed-capital: \"10000000\"",
                "benchmark: \"005930\"",
                "profiles:",
                "  - \"kr-bh-baseline@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                ""), StandardCharsets.UTF_8);
        stopRequest = config.resolve(LiveLabService.STOP_REQUEST);
        service = service(Long.MAX_VALUE);
    }

    private LiveLabService service(long freeBytes) {
        return new LiveLabService(experiments, REPO, config.resolve("live-sessions"), FeedSocket.path(config), stopRequest,
                new CodeVersion("abc", false), Clock.systemUTC(), dir -> free.get() == -1 ? freeBytes : free.get(), 20);
    }

    @AfterEach
    void tearDown() {
        service.close();
        if (gateway != null) {
            gateway.close();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 조건이 맞지 않았습니다");
            }
            Thread.sleep(20);
        }
    }

    private static long events(LiveLabService s) {
        return s.status().session().map(LiveSession.Status::events).orElse(-1L);
    }

    @Test
    void receivesGatewayFeedAndWritesTheLiveReportOnFinish() throws Exception {
        gateway = FeedServer.open(FeedSocket.path(config));
        assertThat(service.start(ID).restoring()).isTrue();
        assertThatThrownBy(() -> service.start(ID)).isInstanceOf(IllegalStateException.class);
        await(() -> gateway.subscriberCount() == 1 && service.status().feedState() == ConnectionState.CONNECTED);

        Instant t = LocalDate.of(2026, 10, 14).atTime(9, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        for (int i = 0; i < 20; i++) {
            Instant at = t.plusSeconds(i * 10L);
            gateway.publish("KIWOOM-WS", new TradeTick(SAMSUNG, i + 1, at, at, new BigDecimal(60000 + i * 100), 5));
        }
        gateway.publish("KIWOOM-WS", new TradeTick(Symbol.krx("000660"), 99, t, t, new BigDecimal("180000"), 1));
        // 받은 순서대로 처리하지만 21번째(boards 밖 종목)까지 처리된 뒤를 기다린다(risk-ops PR #94 F2: 20건만 기다리면 경합)
        await(() -> events(service) == 20
                && service.status().session().map(LiveSession.Status::skippedSymbol).orElse(0L) == 1);
        assertThat(service.status().session().orElseThrow().skippedSymbol()).isEqualTo(1);
        assertThat(service.status().market()).isEqualTo("KRX"); // 화면 시간대는 서버가 준 시장으로(PR #96 R4)
        assertThat(service.status().stopKind()).isEqualTo(LiveLabService.StopKind.NONE);

        LiveLabService.Finished done = service.finish();
        assertThat(done.accounts()).isEqualTo(1);
        assertThat(done.failedAccounts()).isZero();
        assertThat(experiments.resolve(ID).resolve(LiveSession.LIVE_DETAIL_FILE)).isRegularFile();
        assertThat(service.status().session()).isEmpty();
        assertThat(service.status().market()).isEmpty();
        assertThat(service.status().stopReason()).isEqualTo("끝냄");
        assertThat(service.status().stopKind()).isEqualTo(LiveLabService.StopKind.NORMAL);
        await(() -> gateway.subscriberCount() == 0); // 끝내면 전달 연결도 끊는다
    }

    @Test
    void runsWithoutGatewayAndStopsOnWatchdogStopRequest() throws Exception {
        service.start(ID);
        await(() -> service.status().session().isPresent());
        assertThat(service.status().feedState()).isNotEqualTo(ConnectionState.CONNECTED);
        Files.createDirectories(stopRequest.getParent());
        Files.writeString(stopRequest, "");
        await(() -> service.status().session().isEmpty());
        assertThat(service.status().stopReason()).contains("정지를 요청");
        assertThat(service.status().stopKind()).isEqualTo(LiveLabService.StopKind.PROBLEM);
        assertThatThrownBy(() -> service.start(ID)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정지를 요청");

        Files.delete(stopRequest);
        service.start(ID); // 요청을 거두면 다시 시작한다(기록으로 되살림)
        await(() -> service.status().session().isPresent());
        assertThat(service.status().experimentId()).isEqualTo(ID);
    }

    @Test
    void lowDiskRefusesStartAndStopsARunningSession() throws Exception {
        long gb = 1024L * 1024L * 1024L;
        free.set(19 * gb);
        assertThatThrownBy(() -> service.start(ID)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("디스크 여유");

        free.set(100 * gb);
        service.start(ID);
        await(() -> service.status().session().isPresent());
        free.set(5 * gb); // 운용 중 디스크가 줄면 1초 안에 멈춘다
        await(() -> service.status().session().isEmpty() && service.status().experimentId().isEmpty());
        assertThat(service.status().stopReason()).contains("디스크 여유");
        assertThat(service.status().stopKind()).isEqualTo(LiveLabService.StopKind.PROBLEM);
    }

    @Test
    void diskFloorMustBeAtLeastOneGb() {
        // 0이면 하한이 꺼지므로 설정 단계에서 거부한다(QA PR #87 R2)
        for (long bad : new long[] {0, -1, LiveLabService.MAX_MIN_FREE_GB + 1}) {
            assertThatThrownBy(() -> new LiveLabService(experiments, REPO, config.resolve("live-sessions"),
                    FeedSocket.path(config), stopRequest, new CodeVersion("abc", false), Clock.systemUTC(),
                    dir -> Long.MAX_VALUE, bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("min-free-gb");
        }
        // 경계값은 받아들인다(QA PR #91 R2)
        for (long ok : new long[] {1, LiveLabService.MAX_MIN_FREE_GB}) {
            new LiveLabService(experiments, REPO, config.resolve("live-sessions"), FeedSocket.path(config), stopRequest,
                    new CodeVersion("abc", false), Clock.systemUTC(), dir -> Long.MAX_VALUE, ok).close();
        }
    }

    @Test
    void restoreCanBeStoppedAndResumesFromTheJournal() throws Exception {
        // 하루치 기록을 남긴다
        gateway = FeedServer.open(FeedSocket.path(config));
        service.start(ID);
        await(() -> gateway.subscriberCount() == 1 && service.status().feedState() == ConnectionState.CONNECTED);
        Instant t = LocalDate.of(2026, 10, 14).atTime(9, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        gateway.publish("KIWOOM-WS", new TradeTick(SAMSUNG, 1, t, t, new BigDecimal("60000"), 5));
        await(() -> events(service) == 1);
        service.stop("시험");
        assertThat(service.status().stopKind()).isEqualTo(LiveLabService.StopKind.NORMAL);

        // 되살리는 중에 멈추면 기록일 사이에서 그만두고 잠금을 푼다. 정리가 끝나면 다시 시작할 수 있다
        service.start(ID);
        service.stop("되살리는 중 멈춤 시험");
        assertThat(service.status().experimentId()).isEmpty();
        await(() -> {
            try {
                service.start(ID);
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        });
        await(() -> service.status().session().isPresent());
        assertThat(service.status().session().orElseThrow().restored()).isEqualTo(1);
    }

    @Test
    void refusesUnknownOrMalformedIds() {
        assertThatThrownBy(() -> service.start("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start("EXP-NONE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(service::finish).isInstanceOf(IllegalStateException.class);
        assertThat(service.status().experimentId()).isEmpty();
    }

    @Test
    void unreadableRegistrationIsARefusalNotAServerError() throws Exception {
        // 읽기 실패(여기서는 UTF-8이 아닌 바이트)는 500(UncheckedIOException)이 아니라 사용자가 고칠 수 있는 거절로 알린다
        Path dir = Files.createDirectories(experiments.resolve("EXP-TEST-BADBYTES"));
        Files.write(dir.resolve("experiment.yaml"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00});
        assertThatThrownBy(() -> service.start("EXP-TEST-BADBYTES")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("등록 파일을 읽을 수 없습니다")
                .hasMessageContaining("experiments/EXP-TEST-BADBYTES/experiment.yaml");
        assertThat(service.status().experimentId()).isEmpty();
    }

    @Test
    void otherRegistrationKindIsRefusedWithTheCause() throws Exception {
        // 조합 실험 등록 파일은 형식 검사의 "모르는 키입니다: bars-snapshot"이 아니라 원인을 바로 알린다(5단계 QA R5)
        Path combo = Files.createDirectories(experiments.resolve("EXP-TEST-COMBO"));
        Files.writeString(combo.resolve("experiment.yaml"), String.join("\n", "id: \"EXP-TEST-COMBO\"",
                "question: \"q\"", "market: \"KRX\"", "bars-snapshot: \"krx-daily-0001\"", "combinations:",
                "  - \"x\"", ""), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.start("EXP-TEST-COMBO")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("실시간 세션 등록 파일(input: live)이 아닙니다: experiments/EXP-TEST-COMBO/experiment.yaml");
        // 녹화 실험 등록 파일(input: recording)도 같다
        Path rec = Files.createDirectories(experiments.resolve("EXP-TEST-REC"));
        Files.writeString(rec.resolve("experiment.yaml"), "id: \"EXP-TEST-REC\"\ninput: \"recording\"\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.start("EXP-TEST-REC")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("실시간 세션 등록 파일(input: live)이 아닙니다");
        // input: live인데 형식이 틀리면 지금처럼 형식 검사 문구(줄 번호 포함)
        Path broken = Files.createDirectories(experiments.resolve("EXP-TEST-LIVE01"));
        Files.writeString(broken.resolve("experiment.yaml"), "id: \"EXP-TEST-LIVE01\"\ninput: live\nbars-snapshot: x\n",
                StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.start("EXP-TEST-LIVE01")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("experiments/EXP-TEST-LIVE01/experiment.yaml:3: 모르는 키입니다: bars-snapshot");
        assertThat(service.status().experimentId()).isEmpty();
    }

    @Test
    void listsLiveRegistrationsOnly() throws Exception {
        Path combo = Files.createDirectories(experiments.resolve("EXP-TEST-COMBO"));
        Files.writeString(combo.resolve("experiment.yaml"), "id: \"EXP-TEST-COMBO\"\ncombinations:\n  - \"x\"\n",
                StandardCharsets.UTF_8);
        Path broken = Files.createDirectories(experiments.resolve("EXP-TEST-LIVE01"));
        Files.writeString(broken.resolve("experiment.yaml"), "id: \"EXP-TEST-LIVE01\"\ninput: live\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(experiments.resolve("EXP-TEST-EMPTY"));

        assertThat(service.registrations()).extracting(LiveLabService.Registration::id)
                .containsExactly(ID, "EXP-TEST-LIVE01");
        LiveLabService.Registration ok = service.registrations().get(0);
        assertThat(ok.error()).isNull();
        assertThat(ok.profiles()).containsExactly("kr-bh-baseline@1");
        assertThat(ok.period()).isEqualTo("2026-10-14 ~ 2026-10-14");
        assertThat(ok.symbols()).isEqualTo(1);
        assertThat(ok.hasJournal()).isFalse();
        assertThat(ok.finished()).isFalse();
        assertThat(service.registrations().get(1).error()).isNotBlank();

        service.start(ID); // 세션을 열면 입력 기록 폴더가 생겨 "이어서 시작"이 된다
        await(() -> service.status().session().isPresent());
        assertThat(service.status().profiles()).containsExactly("kr-bh-baseline@1");
        service.stop("시험");
        assertThat(service.registrations().get(0).hasJournal()).isTrue();
    }

    @Test
    void selectionAccountsAreListedAfterProfilesAndPreparedOffTheEventThread() throws Exception {
        // D-124: 선정 조합 계좌는 profiles 뒤에 "선정 x 전략"으로 보이고, 준비는 별도 스레드가 한다. 스냅샷이 없으면 사유만 남긴다
        String id = "EXP-TEST-LIVESEL";
        Path dir = Files.createDirectories(experiments.resolve(id));
        Files.writeString(dir.resolve("experiment.yaml"), String.join("\n",
                "id: \"" + id + "\"",
                "question: \"q\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-15\"",
                "seed: \"1\"",
                "seed-capital: \"10000000\"",
                "profiles:",
                "  - \"kr-orb-30@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "selections:",
                "  - \"kr-intraday-hot@1 x kr-random-control@1\"",
                "selection-input:",
                "  daily: \"latest\"",
                ""), StandardCharsets.UTF_8);
        assertThat(service.registrations()).filteredOn(r -> r.id().equals(id)).singleElement()
                .satisfies(r -> assertThat(r.profiles()).containsExactly("kr-orb-30@1",
                        "kr-intraday-hot@1 x kr-random-control@1"));
        service.close();
        // 화요일 17:00(현지): 장 밖 창이라 다음 거래일 2026-10-14를 준비한다
        service = new LiveLabService(experiments, REPO, config.resolve("live-sessions"), FeedSocket.path(config),
                stopRequest, new CodeVersion("abc", false), Clock.fixed(Instant.parse("2026-10-13T08:00:00Z"),
                        ZoneOffset.UTC), dir2 -> Long.MAX_VALUE, 20);
        service.start(id);
        await(() -> service.status().prepare().isPresent());
        LiveLabService.View view = service.status();
        assertThat(view.profiles()).containsExactly("kr-orb-30@1", "kr-intraday-hot@1 x kr-random-control@1");
        LiveSelectionPrep.Report prep = view.prepare().orElseThrow();
        assertThat(prep.date()).isEqualTo(java.time.LocalDate.of(2026, 10, 14));
        assertThat(prep.ready()).isFalse();
        assertThat(prep.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(SelectionPreparer.Status.PROBLEM);
            assertThat(o.reason()).isEqualTo(SelectionInvalidReason.STALE_INPUT);
        });
        service.prepareNow(); // 다시 요청해도 이벤트 처리와 별도 스레드에서 한다
        service.stop("시험");
        assertThatThrownBy(service::prepareNow).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prepareNowNeedsSelectionAccounts() throws Exception {
        service.start(ID);
        await(() -> service.status().session().isPresent());
        assertThatThrownBy(service::prepareNow).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("selections");
        assertThat(service.status().prepare()).isEmpty();
    }

    @Test
    void heapCheckKeepsHalfTheHeapForTheSession() {
        long fourGb = 4L << 30;
        assertThat(LiveSelectionPrep.heapFits(1_000_000, fourGb)).isTrue();
        assertThat(LiveSelectionPrep.heapFits(3_000_000, fourGb)).isFalse();
    }

    @Test
    void showsRecentGapsNewestFirst() throws Exception {
        assertThat(service.gaps(10).total()).isZero(); // 세션이 없으면 빈 목록
        assertThatThrownBy(() -> service.gaps(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.gaps(LiveLabService.MAX_GAPS_SHOWN + 1))
                .isInstanceOf(IllegalArgumentException.class);

        gateway = FeedServer.open(FeedSocket.path(config));
        service.start(ID);
        await(() -> gateway.subscriberCount() == 1 && service.status().feedState() == ConnectionState.CONNECTED);
        Instant t = LocalDate.of(2026, 10, 14).atTime(9, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        gateway.publish("KIWOOM-WS", new TradeTick(SAMSUNG, 1, t, t, new BigDecimal("60000"), 5));
        gateway.publish("KIWOOM-WS", new DataGap(SAMSUNG, 2, t.plusSeconds(60), t.plusSeconds(60), t,
                t.plusSeconds(60), DataGap.GapReason.DISCONNECTED));
        gateway.publish("KIWOOM-WS", new DataGap(SAMSUNG, 3, t.plusSeconds(120), t.plusSeconds(120),
                t.plusSeconds(90), t.plusSeconds(120), DataGap.GapReason.SOURCE_SWITCH));
        await(() -> service.gaps(10).total() == 2);

        LiveLabService.Gaps gaps = service.gaps(1);
        assertThat(gaps.total()).isEqualTo(2);
        assertThat(gaps.items()).singleElement().satisfies(g -> {
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.SOURCE_SWITCH);
            assertThat(g.symbol()).isEqualTo("005930");
            assertThat(g.gapStart()).isEqualTo(t.plusSeconds(90));
        });
        assertThat(service.gaps(10).items()).extracting(LiveLabService.GapView::reason)
                .containsExactly(DataGap.GapReason.SOURCE_SWITCH, DataGap.GapReason.DISCONNECTED);
    }

    @Test
    void reportWriteFailureAfterFinishIsExplainedAndLeftInStatus() throws Exception {
        service.start(ID);
        await(() -> service.status().session().isPresent());
        // 보고서 자리에 폴더가 있으면 쓰기가 실패한다(권한 시험은 root에서 재현되지 않음)
        Files.createDirectories(experiments.resolve(ID).resolve(LiveSession.LIVE_DETAIL_FILE));
        assertThatThrownBy(service::finish).isInstanceOf(LiveLabService.ReportNotWrittenException.class)
                .hasMessageContaining("보고서를 쓰지 못했습니다")
                .hasMessageContaining("experiments/" + ID + "/" + LiveSession.LIVE_DETAIL_FILE)
                .hasMessageNotContaining(experiments.toString());
        assertThat(service.status().experimentId()).isEmpty();
        assertThat(service.status().stopReason()).contains("보고서를 쓰지 못했습니다");
    }

    @Test
    void finishFailureBeforeTheReportLeavesOnlyTheExceptionKind() throws Exception {
        // PR #96 N1: 마감·기록 닫기 실패 분기. 기록 파일을 지우면 DayWriter.close의 SHA-256 계산이 실패한다
        gateway = FeedServer.open(FeedSocket.path(config));
        service.start(ID);
        await(() -> gateway.subscriberCount() == 1 && service.status().feedState() == ConnectionState.CONNECTED);
        Instant t = LocalDate.of(2026, 10, 14).atTime(9, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        gateway.publish("KIWOOM-WS", new TradeTick(SAMSUNG, 1, t, t, new BigDecimal("60000"), 5));
        await(() -> events(service) == 1);
        Path events;
        try (Stream<Path> files = Files.walk(config.resolve("live-sessions"))) {
            events = files.filter(f -> f.getFileName().toString().equals("events.jsonl.gz")).findFirst().orElseThrow();
        }
        Files.delete(events);

        assertThatThrownBy(service::finish).isInstanceOf(UncheckedIOException.class);
        LiveLabService.View after = service.status();
        assertThat(after.experimentId()).isEmpty();
        assertThat(after.stopReason()).isEqualTo("끝내기 실패(UncheckedIOException). 실험실 로그를 확인하세요")
                .doesNotContain(config.toString());
        assertThat(after.stopKind()).isEqualTo(LiveLabService.StopKind.PROBLEM);
        assertThat(experiments.resolve(ID).resolve(LiveSession.LIVE_DETAIL_FILE)).doesNotExist();
    }

    @Test
    void startFailureShowsTheExceptionKindWithoutPaths() throws Exception {
        // PR #96 N2: 세션 폴더 자리에 파일이 있으면 세션을 열 수 없다. 화면 사유에는 예외 종류만, 절대 경로는 남기지 않는다
        Files.createDirectories(config.resolve("live-sessions"));
        Files.writeString(config.resolve("live-sessions").resolve(ID), "not a folder");
        service.start(ID);
        await(() -> service.status().experimentId().isEmpty());
        LiveLabService.View after = service.status();
        assertThat(after.stopReason()).startsWith("시작 실패(").endsWith("). 실험실 로그를 확인하세요")
                .doesNotContain(config.toString());
        assertThat(after.stopKind()).isEqualTo(LiveLabService.StopKind.PROBLEM);
    }
}
