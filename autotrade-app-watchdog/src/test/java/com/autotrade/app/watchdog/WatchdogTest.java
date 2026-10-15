package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.watchdog.HeartbeatWatch.State;
import com.autotrade.monitor.Heartbeat;
import com.autotrade.monitor.HeartbeatWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatchdogTest {

    static final long GIB = 1L << 30;

    @TempDir
    Path dir;

    private final AtomicLong free = new AtomicLong(100 * GIB);
    private volatile boolean diskFails;

    private Watchdog watchdog(TestClock clock) {
        WatchdogSettings settings = WatchdogSettings.parse("release-hold-seconds=60", dir);
        return new Watchdog(dir, settings, clock, path -> {
            if (diskFails) {
                throw new IOException("읽기 실패 주입");
            }
            return free.get();
        });
    }

    @Test
    void 드라이브_여유가_위험_아래로_내려가면_정지_요청_파일을_만들고_회복이_이어지면_지운다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        Path collector = dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST);
        Path experiment = dir.resolve(StopRequests.EXPERIMENT_STOP_REQUEST);

        w.tick();
        assertThat(collector).doesNotExist();

        free.set(19 * GIB);
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(collector).exists();
        assertThat(experiment).exists();

        free.set(40 * GIB); // 경고 구간: 유지
        clock.advance(Duration.ofSeconds(60));
        w.tick();
        assertThat(experiment).exists();

        free.set(60 * GIB);
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        clock.advance(Duration.ofSeconds(59));
        w.tick();
        assertThat(experiment).exists();
        clock.advance(Duration.ofSeconds(1));
        w.tick();
        assertThat(collector).doesNotExist();
        assertThat(experiment).doesNotExist();
        w.close();
    }

    @Test
    void 드라이브를_읽지_못하면_판단_못_함이라_정지_요청을_풀지_않는다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        free.set(10 * GIB);
        w.tick();
        assertThat(w.stopRequests().active()).isTrue();

        diskFails = true;
        free.set(100 * GIB);
        for (int i = 0; i < 30; i++) {
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.stopRequests().active()).isTrue();
        w.close();
    }

    @Test
    void 점검마다_자기_하트비트를_쓰고_다른_프로세스_하트비트를_판정한다() throws Exception {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        new HeartbeatWriter(dir, "gateway", clock).beat();

        w.tick();
        Heartbeat own = Heartbeat.parse(Files.readString(Heartbeat.file(dir, "watchdog"), StandardCharsets.UTF_8)).orElseThrow();
        assertThat(own.writtenAt()).isEqualTo(clock.instant());
        assertThat(w.heartbeats().states()).containsEntry("engine", State.ABSENT).containsEntry("gateway", State.ALIVE)
                .containsEntry("lab", State.ABSENT);

        clock.advance(Duration.ofSeconds(20));
        w.tick();
        assertThat(w.heartbeats().states()).containsEntry("gateway", State.STALE);
        assertThat(Heartbeat.parse(Files.readString(Heartbeat.file(dir, "watchdog"), StandardCharsets.UTF_8)).orElseThrow().seq())
                .isEqualTo(2);

        w.close();
        assertThat(Heartbeat.file(dir, "watchdog")).doesNotExist(); // 정상 종료 표시
    }

    @Test
    void 닫아도_정지_요청_파일은_남겨_다음_감시가_이어받는다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        free.set(10 * GIB);
        w.tick();
        w.close();
        assertThat(dir.resolve(StopRequests.EXPERIMENT_STOP_REQUEST)).exists();

        Watchdog next = watchdog(clock);
        assertThat(next.stopRequests().active()).isTrue();
        next.close();
    }

    @Test
    void 주기_점검을_스레드에서_돌린다() throws Exception {
        WatchdogSettings settings = WatchdogSettings.parse("interval-seconds=1", dir);
        Watchdog w = new Watchdog(dir, settings, java.time.Clock.systemUTC(), path -> 100 * GIB);
        w.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(Heartbeat.file(dir, "watchdog")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(Heartbeat.file(dir, "watchdog")).exists();
        } finally {
            w.close();
        }
    }
}
