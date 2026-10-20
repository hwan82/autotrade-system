package com.autotrade.app.lab.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.marketdata.feed.FeedSocket;
import com.autotrade.marketdata.symbols.SymbolNames;
import com.autotrade.sim.run.CodeVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/** 실시간 모의 API 오류 응답: 사용자가 고칠 수 있는 실패는 500이 아니라 400·409로 나간다. */
class LiveSessionControllerTest {

    @TempDir
    Path experiments;

    @TempDir
    Path config;

    LiveLabService service;
    LiveSessionController controller;

    @BeforeEach
    void setUp() {
        service = new LiveLabService(experiments, Path.of(".."), config.resolve("live-sessions"), FeedSocket.path(config),
                config.resolve(LiveLabService.STOP_REQUEST), new CodeVersion("abc", false), Clock.systemUTC(),
                dir -> Long.MAX_VALUE, 20);
        controller = new LiveSessionController(service, SymbolNames.empty());
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void unreadableRegistrationIsConflict() throws Exception {
        Path dir = Files.createDirectories(experiments.resolve("EXP-TEST-BADBYTES"));
        Files.write(dir.resolve("experiment.yaml"), new byte[] {(byte) 0xFF, (byte) 0xFE});
        assertThatThrownBy(() -> controller.start("EXP-TEST-BADBYTES")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void comboRegistrationIsBadRequestNamingTheCause() throws Exception {
        Path dir = Files.createDirectories(experiments.resolve("EXP-TEST-COMBO"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: \"EXP-TEST-COMBO\"\nbars-snapshot: \"krx-daily-0001\"\n");
        assertThatThrownBy(() -> controller.start("EXP-TEST-COMBO")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains("실시간 세션 등록 파일(input: live)이 아닙니다");
        });
    }

    @Test
    void badRequestsAreRefused() {
        assertThatThrownBy(() -> controller.start("../x")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.gaps(0)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(controller::finish).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(controller.registrations()).isEmpty();
        assertThat(controller.gaps(100).total()).isZero();
    }

    @Test
    void accountActivityIsNotFoundWithoutARunningSession() {
        assertThatThrownBy(() -> controller.fills("EXP-TEST-LIVE03-A01", 0, 50)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.rejections("EXP-TEST-LIVE03-A01", null, null, 0, 50))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void accountActivityOfTheRunningSession() throws Exception {
        String id = "EXP-TEST-LIVE04";
        Path dir = Files.createDirectories(experiments.resolve(id));
        Files.writeString(dir.resolve("experiment.yaml"), String.join("\n", "id: \"" + id + "\"", "question: \"q\"",
                "input: \"live\"", "market: \"KRX\"", "from: \"2026-10-14\"", "to: \"2026-10-14\"", "seed: \"1\"",
                "seed-capital: \"10000000\"", "profiles:", "  - \"kr-bh-baseline@1\"", "boards:", "  - \"005930 KOSPI\"", ""),
                java.nio.charset.StandardCharsets.UTF_8);
        controller.start(id);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (service.status().session().isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
        String runId = service.status().session().orElseThrow().runs().get(0).runId();
        assertThat(controller.fills(runId, 0, 50).total()).isZero();
        assertThat(controller.rejections(runId, null, null, 0, 50).counts()).isEmpty();
        assertThatThrownBy(() -> controller.fills(id + "-A99", 0, 50)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.fills(runId, 0, 0)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void reportWriteFailureIsReportNotWrittenWithoutAbsolutePath() throws Exception {
        String id = "EXP-TEST-LIVE03";
        Path dir = Files.createDirectories(experiments.resolve(id));
        Files.writeString(dir.resolve("experiment.yaml"), String.join("\n", "id: \"" + id + "\"", "question: \"q\"",
                "input: \"live\"", "market: \"KRX\"", "from: \"2026-10-14\"", "to: \"2026-10-14\"", "seed: \"1\"",
                "seed-capital: \"10000000\"", "profiles:", "  - \"kr-bh-baseline@1\"", "boards:", "  - \"005930 KOSPI\"", ""),
                java.nio.charset.StandardCharsets.UTF_8);
        controller.start(id);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (service.status().session().isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
        Files.createDirectories(dir.resolve(com.autotrade.sim.live.LiveSession.LIVE_DETAIL_FILE));
        assertThatThrownBy(controller::finish).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(e.code()).isEqualTo("REPORT_NOT_WRITTEN");
            assertThat(e.getMessage()).contains("experiments/" + id + "/live-detail.txt")
                    .doesNotContain(experiments.toString());
        });
        assertThat(controller.status().stopReason()).contains("보고서를 쓰지 못했습니다");
    }
}
