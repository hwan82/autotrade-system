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
    /** PC CPU 사용률 0~1(음수면 모름), 메모리 사용률 %. */
    private volatile double cpu = 0.1;
    private volatile double memoryPercent = 40;

    /** 시험용 PC 값. 메모리 전체 1000바이트에 사용률만큼 쓰인 것으로 돌려준다. */
    private final OsReadings os = new OsReadings() {
        @Override
        public double cpuLoad() {
            return cpu;
        }

        @Override
        public long totalMemoryBytes() {
            return 1000;
        }

        @Override
        public long freeMemoryBytes() {
            return Math.round(1000 - memoryPercent * 10);
        }
    };

    private Watchdog watchdog(TestClock clock) {
        WatchdogSettings settings = WatchdogSettings.parse("release-hold-seconds=60\ncpu.system=85:95:2\nmemory.used=85:95:1", dir);
        return new Watchdog(dir, settings, clock, path -> {
            if (diskFails) {
                throw new IOException("읽기 실패 주입");
            }
            return free.get();
        }, os);
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
    void CPU가_위험으로_연속되면_정지_요청을_만들고_경고_아래로_내려가_이어지면_푼다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        cpu = 0.97;
        w.tick();
        assertThat(w.stopRequests().active()).isFalse(); // 연속 2회 기준이라 한 번은 아직
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.stopRequests().active()).isTrue();
        assertThat(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST)).exists();

        cpu = 0.90; // 경고 구간: 유지
        for (int i = 0; i < 20; i++) {
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.stopRequests().active()).isTrue();

        cpu = 0.5; // 정상 판정도 연속 2회가 돼야 수준이 바뀌고, 그때부터 해제 유지 시간을 센다
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        clock.advance(Duration.ofSeconds(59));
        w.tick();
        assertThat(w.stopRequests().active()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        w.tick();
        assertThat(w.stopRequests().active()).isFalse();
        w.close();
    }

    @Test
    void 메모리가_위험이면_정지_요청을_만든다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        memoryPercent = 96;
        w.tick();
        assertThat(w.stopRequests().active()).isTrue();
        w.close();
    }

    @Test
    void CPU를_모르면_판단_못_함이라_정지_요청을_풀지_않는다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        memoryPercent = 96;
        w.tick();
        assertThat(w.stopRequests().active()).isTrue();

        memoryPercent = 40;
        cpu = -1; // OS가 아직 값을 못 줌
        for (int i = 0; i < 30; i++) {
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.stopRequests().active()).isTrue();
        w.close();
    }

    @Test
    void 값이_빠지기_시작하면_한_번만_알리고_돌아오면_다시_알릴_수_있다() {
        // PR #122 risk-ops R1: 값이 소리 없이 계속 빠지면 위험 판정이 꺼진 것을 아무도 모른다
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        assertThat(w.missingWarnings()).isZero();

        cpu = -1;
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.missingWarnings()).isEqualTo(1);

        diskFails = true; // 빠진 값이 늘면 다시 알린다
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(2);

        diskFails = false;
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(3);
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
        Watchdog w = new Watchdog(dir, settings, java.time.Clock.systemUTC(), path -> 100 * GIB, os);
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
