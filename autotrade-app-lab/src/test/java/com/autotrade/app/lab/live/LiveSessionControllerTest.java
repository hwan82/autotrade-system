package com.autotrade.app.lab.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.marketdata.feed.FeedSocket;
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
        controller = new LiveSessionController(service);
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
}
