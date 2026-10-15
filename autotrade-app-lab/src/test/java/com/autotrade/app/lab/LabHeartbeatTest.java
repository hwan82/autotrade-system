package com.autotrade.app.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.RuntimeConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;

/** 실험실 하트비트는 진입점에서 시작하고 컨텍스트가 닫히면 지운다(D-092, architect PR #116 A2). */
class LabHeartbeatTest {

    @TempDir
    Path dir;

    @Test
    void 설정_폴더에_하트비트를_쓰고_컨텍스트가_닫히면_지운다() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(RuntimeConfigStore.class, () -> new RuntimeConfigStore(ConfigPaths.in(dir)));
        context.registerBean(Clock.class, Clock::systemUTC);
        context.refresh();
        Path file = dir.resolve("heartbeat").resolve("lab.hb");

        AutotradeLabApplication.startHeartbeat(context);
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(Files.readString(file)).contains("process=lab");

        context.close();
        assertThat(file).doesNotExist();
    }
}
