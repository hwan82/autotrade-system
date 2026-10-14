package com.autotrade.app.lab.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.feed.FeedSocket;
import com.autotrade.sim.live.LiveSession;
import com.autotrade.sim.run.CodeVersion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.BooleanSupplier;
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
        await(() -> events(service) == 20);
        assertThat(service.status().session().orElseThrow().skippedSymbol()).isEqualTo(1);

        LiveLabService.Finished done = service.finish();
        assertThat(done.accounts()).isEqualTo(1);
        assertThat(done.failedAccounts()).isZero();
        assertThat(experiments.resolve(ID).resolve(LiveSession.LIVE_DETAIL_FILE)).isRegularFile();
        assertThat(service.status().session()).isEmpty();
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
}
