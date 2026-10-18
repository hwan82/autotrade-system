package com.autotrade.app.watchdog;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.watchdog.HeartbeatWatch.State;
import com.autotrade.monitor.Heartbeat;
import com.autotrade.monitor.HeartbeatWriter;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.LogChannel;
import com.autotrade.notification.NotificationQueue;
import com.autotrade.notification.Notifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
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

    /** 넣은 알림 한 건. */
    record Sent(String kind, String subject, String title, String body) {}

    private final List<Sent> sent = new ArrayList<>();

    private Watchdog watchdog(TestClock clock) {
        return watchdog(clock, (kind, subject, title, body) -> sent.add(new Sent(kind, subject, title, body)), stage -> { });
    }

    private Watchdog watchdog(TestClock clock, Alerts alerts, java.util.function.Consumer<Watchdog.Stage> probe) {
        WatchdogSettings settings = WatchdogSettings.parse("release-hold-seconds=60\ncpu.system=85:95:2\nmemory.used=85:95:1", dir);
        return new Watchdog(dir, settings, clock, path -> {
            if (diskFails) {
                throw new IOException("읽기 실패 주입");
            }
            return free.get();
        }, os, alerts, null, probe);
    }

    /** 이 단계에서 던지는 감시. 실패시킬 단계는 {@code failing[0]}으로 점검 사이에 바꿀 수 있다(null이면 안 던짐). */
    private Watchdog failingAt(TestClock clock, Watchdog.Stage[] failing) {
        return watchdog(clock, (kind, subject, title, body) -> sent.add(new Sent(kind, subject, title, body)), stage -> {
            if (stage == failing[0]) {
                throw new IllegalStateException("단계 실패 주입");
            }
        });
    }

    private List<String> kinds() {
        return sent.stream().map(Sent::kind).toList();
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
    void 값이_빠지면_바로_알리고_계속_빠지면_간격마다_다시_알린다() {
        // PR #122 risk-ops R1·독립 QA R1: 값이 소리 없이 계속 빠지면 위험 판정이 꺼진 것을 아무도 모른다(D-096)
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        assertThat(w.missingWarnings()).isZero();

        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(1);
        clock.advance(Watchdog.MISSING_REPEAT.minusSeconds(5)); // 간격 직전
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(2);

        diskFails = true; // 새 값이 빠지면 간격과 상관없이 바로
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(3);

        diskFails = false;
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingRecoveries()).isEqualTo(1);
        w.close();
    }

    @Test
    void 같은_값이_빠졌다_돌아오기를_되풀이해도_간격마다_정해진_횟수만_알린다() {
        // PR #122 독립 QA R4: 점검마다 오가면 WARN·INFO가 5초마다 쌓였다
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        for (int i = 0; i < 60; i++) { // 5분 동안 한 점검씩 번갈아 빠짐
            cpu = i % 2 == 0 ? -1 : 0.1;
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        // 5초 WARN, 10초 회복 INFO, 15초 다시 빠져 바로 WARN(D-108, PR #123 QA R1). 그 뒤 회복 INFO는 간격까지 미루고 조용하다
        assertThat(w.missingWarnings()).isEqualTo(2);
        assertThat(w.missingRecoveries()).isEqualTo(1);

        clock.advance(Watchdog.MISSING_REPEAT);
        cpu = -1;
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(3);

        for (int i = 0; i < 20; i++) { // 두 값이 번갈아 빠짐: 둘 다 한 번씩 알린 뒤로는 간격 안에서 조용하다
            cpu = i % 2 == 0 ? 0.1 : -1;
            diskFails = i % 2 == 0;
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.missingWarnings()).isEqualTo(4);
        assertThat(w.missingRecoveries()).isEqualTo(1);
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

    @Test
    void 엔진_하트비트가_멈추면_긴급_알림을_넣고_회복과_정상_종료는_정보_알림을_넣는다() throws Exception {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        HeartbeatWriter engine = new HeartbeatWriter(dir, "engine", clock);
        engine.beat();
        w.tick();
        // 처음 본 살아 있음·꺼짐은 알리지 않는다
        assertThat(kinds()).isEmpty();

        clock.advance(Duration.ofSeconds(20));
        w.tick();
        assertThat(sent).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo(EventRoutes.PROCESS_STALE);
            assertThat(a.subject()).isEqualTo("engine");
            assertThat(a.body()).contains("장애표 #8");
        });
        assertThat(EventRoutes.defaults().severityOf(sent.get(0).kind()))
                .isEqualTo(com.autotrade.notification.Severity.URGENT);

        engine.beat();
        clock.advance(Duration.ofSeconds(1));
        w.tick();
        assertThat(kinds()).containsExactly(EventRoutes.PROCESS_STALE, EventRoutes.PROCESS_RECOVERED);

        engine.close(); // 정상 종료는 파일을 지운다
        clock.advance(Duration.ofSeconds(1));
        w.tick();
        assertThat(kinds()).containsExactly(EventRoutes.PROCESS_STALE, EventRoutes.PROCESS_RECOVERED,
                EventRoutes.PROCESS_STOPPED);
        w.close();
    }

    @Test
    void 기동_때_이미_멈춘_엔진은_첫_점검에_바로_알린다() throws Exception {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        new HeartbeatWriter(dir, "engine", clock).beat();
        clock.advance(Duration.ofMinutes(5));
        Watchdog w = watchdog(clock);
        w.tick();
        assertThat(sent).filteredOn(a -> a.subject().equals("engine")).extracting(Sent::kind)
                .containsExactly(EventRoutes.PROCESS_STALE);
        w.close();
    }

    @Test
    void 점검_사이에_엔진이_다시_뜨면_재기동_알림을_넣는다() throws Exception {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        new HeartbeatWriter(dir, "engine", clock).beat();
        w.tick();
        clock.advance(Duration.ofSeconds(3));
        new HeartbeatWriter(dir, "engine", clock).beat(); // 새 시작 시각
        w.tick();
        assertThat(sent).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo(EventRoutes.PROCESS_RESTARTED);
            assertThat(a.subject()).isEqualTo("engine");
        });
        w.close();
    }

    @Test
    void 정지_요청이_켜지면_긴급_꺼지면_정보_알림을_넣는다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        free.set(10 * GIB);
        w.tick();
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 켜진 채 유지: 다시 알리지 않는다
        assertThat(kinds()).containsExactly(EventRoutes.STOP_REQUEST_ON);
        assertThat(sent.get(0).body()).contains("실전 엔진은 멈추지 않습니다");

        free.set(100 * GIB);
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        clock.advance(Duration.ofSeconds(60));
        w.tick();
        assertThat(w.stopRequests().active()).isFalse();
        assertThat(kinds()).containsExactly(EventRoutes.STOP_REQUEST_ON, EventRoutes.STOP_REQUEST_OFF);
        w.close();
    }

    @Test
    void 자원_값은_새로_빠질_때와_돌아올_때만_알리고_10분_반복은_로그로만_남긴다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        clock.advance(Watchdog.MISSING_REPEAT);
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(2);
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING);
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING, EventRoutes.READING_RECOVERED);
        w.close();
    }

    @Test
    void 알림이_던져도_점검은_계속한다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        WatchdogSettings settings = WatchdogSettings.parse("release-hold-seconds=60", dir);
        Watchdog w = new Watchdog(dir, settings, clock, path -> 10 * GIB, os, (kind, subject, title, body) -> {
            throw new IllegalStateException("알림 실패 주입");
        }, null);
        w.tick();
        assertThat(w.stopRequests().active()).isTrue();
        assertThat(dir.resolve(StopRequests.EXPERIMENT_STOP_REQUEST)).exists();
        w.close();
    }

    @Test
    void 회복_알림_뒤_다시_빠지면_간격을_기다리지_않고_바로_알린다() {
        // PR #123 독립 QA R1: 5초 WARN → 10초 회복 INFO → 15초부터 계속 빠짐이면 다음 WARN이 10분 5초였다(595초 동안 마지막 로그가 "다시 읽음")
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingRecoveries()).isEqualTo(1);

        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(2);
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING, EventRoutes.READING_RECOVERED, EventRoutes.READING_MISSING);
        for (int i = 0; i < 119; i++) { // 계속 빠진 채 간격 직전(10분 10초)까지는 조용하다
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.missingWarnings()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(3); // 다시 빠진 경고(15초)부터 10분
        assertThat(kinds()).hasSize(3); // 10분 반복은 로그로만
        w.close();
    }

    @Test
    void 간격_안에_두_번째_회복은_미뤘다가_간격이_찬_뒤_값이_다_있으면_남긴다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 5초 WARN
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 10초 INFO
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 15초 WARN
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 20초: 간격 안 두 번째 회복이라 미룸(마지막 로그는 "읽지 못함", 안전한 쪽)
        assertThat(w.missingRecoveries()).isEqualTo(1);
        clock.advance(Watchdog.MISSING_REPEAT.minusSeconds(15)); // 첫 INFO(10초)에서 간격 직전
        w.tick();
        assertThat(w.missingRecoveries()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(5)); // 첫 INFO에서 정확히 10분
        w.tick();
        assertThat(w.missingRecoveries()).isEqualTo(2);
        assertThat(w.missingWarnings()).isEqualTo(2);
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING, EventRoutes.READING_RECOVERED, EventRoutes.READING_MISSING,
                EventRoutes.READING_RECOVERED);
        clock.advance(Duration.ofMinutes(30)); // 그 뒤로는 조용하다
        w.tick();
        assertThat(w.missingRecoveries()).isEqualTo(2);
        w.close();
    }

    @Test
    void 점검마다_오가도_간격마다_WARN_둘_INFO_하나를_넘지_않고_마지막_로그가_틀린_회복이_아니다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        List<LogRecord> records = new ArrayList<>();
        Handler handler = capture(records);
        try {
            Watchdog w = watchdog(clock);
            w.tick();
            int intervals = 6;
            int ticks = (int) (Watchdog.MISSING_REPEAT.toSeconds() / 5) * intervals; // 60분, 5초마다 번갈아 빠짐
            for (int i = 0; i < ticks; i++) {
                cpu = i % 2 == 0 ? -1 : 0.1;
                clock.advance(Duration.ofSeconds(5));
                w.tick();
                boolean missingNow = cpu < 0;
                List<LogRecord> missingLogs = records.stream().filter(r -> r.getMessage().startsWith("자원 값")).toList();
                if (missingNow) {
                    // 빠진 점검 뒤 마지막 결측 로그는 늘 "읽지 못함"이다(QA R1)
                    assertThat(missingLogs.get(missingLogs.size() - 1).getMessage()).startsWith("자원 값을 읽지 못함");
                }
            }
            w.close();
            assertThat(w.missingWarnings()).isLessThanOrEqualTo(2 * intervals);
            assertThat(w.missingRecoveries()).isLessThanOrEqualTo(intervals);
        } finally {
            java.util.logging.Logger.getLogger(Watchdog.class.getName()).removeHandler(handler);
        }
    }

    @Test
    void 드라이브를_읽지_못한_원인을_결측_경고에_적는다() {
        // PR #119 risk-ops K3·QA R3: 드라이브 읽기 실패가 원인 없이 값만 빠졌다
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        List<LogRecord> records = new ArrayList<>();
        Handler handler = capture(records);
        try {
            Watchdog w = watchdog(clock);
            diskFails = true;
            w.tick();
            w.close();
            assertThat(records).filteredOn(r -> r.getMessage().startsWith("자원 값을 읽지 못함")).singleElement()
                    .satisfies(r -> assertThat(r.getMessage())
                            .contains("(원인: " + DiskFreeSource.readingName(dir) + " ← IOException: 읽기 실패 주입)")
                            .contains("D-096"));
        } finally {
            java.util.logging.Logger.getLogger(Watchdog.class.getName()).removeHandler(handler);
        }
    }

    @Test
    void 드라이브_둘_중_하나만_실패하면_원인은_그_드라이브에만_붙는다() throws Exception {
        // PR #157 QA Q1(변이 M13): 실패 맵의 원인을 다른 빠진 값(다른 드라이브, CPU)에 붙이면 안 된다
        Path a = dir.resolve("a");
        Path b = dir.resolve("b");
        java.nio.file.Files.createDirectories(a);
        java.nio.file.Files.createDirectories(b);
        WatchdogSettings settings = WatchdogSettings.parse("disks=" + a + "," + b, dir);
        Watchdog w = new Watchdog(dir, settings, new TestClock("2026-10-15T00:00:00Z"), path -> {
            if (path.equals(settings.disks().get(1))) {
                throw new IOException("fail b");
            }
            return 100 * GIB;
        }, os);
        List<LogRecord> records = new ArrayList<>();
        Handler handler = capture(records);
        try {
            cpu = -1;
            w.tick();
            w.close();
        } finally {
            java.util.logging.Logger.getLogger(Watchdog.class.getName()).removeHandler(handler);
        }
        String nameB = DiskFreeSource.readingName(settings.disks().get(1));
        assertThat(records).filteredOn(r -> r.getMessage().startsWith("자원 값을 읽지 못함")).singleElement()
                .satisfies(r -> assertThat(r.getMessage())
                        .startsWith("자원 값을 읽지 못함: [cpu.system, " + nameB + "] (원인: " + nameB + " ← IOException: fail b). "));
    }

    @Test
    void 미룬_회복_INFO는_지난_회복_뒤_경고한_값만_적는다() {
        // risk-ops PR #157 K3: 간격 안에서 합친 경고 목록(이미 회복을 알린 값 포함)을 회복 본문에 적지 않는다
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 5초 WARN [cpu]
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 10초 INFO [cpu]
        diskFails = true;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 15초 WARN [disk]
        diskFails = false;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 20초 회복은 미룸
        clock.advance(Watchdog.MISSING_REPEAT.minusSeconds(10));
        w.tick(); // 첫 INFO에서 10분: 미룬 회복
        w.close();
        String disk = DiskFreeSource.readingName(dir.toAbsolutePath());
        assertThat(sent).filteredOn(a -> a.kind().equals(EventRoutes.READING_RECOVERED)).extracting(Sent::body)
                .containsExactly("[cpu.system]", "[" + disk + "]");
    }

    @Test
    void 감시_로그가_던져도_같은_점검의_알림과_정지_요청은_그대로_한다() throws Exception {
        // risk-ops PR #155 K1: 점검 단계는 상태를 바꾼 뒤 로그를 낸다. 그 로그가 던지면 하트비트 멈춤 알림이 영영 나가지 않고,
        // 정지 요청 파일·자원 결측 알림이 그 점검에서 빠졌다. 자기 하트비트 기록 실패 로그(monitor 패키지)도 같다(PR #163 architect N1).
        // 감시와 감시 에이전트 코어 로그 모두에 던지는 핸들러를 붙인다
        java.util.logging.Logger pkg = java.util.logging.Logger.getLogger("com.autotrade");
        Handler throwing = new Handler() {
            @Override
            public void publish(LogRecord r) {
                throw new IllegalStateException("핸들러 예외 주입");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        pkg.addHandler(throwing);
        try {
            TestClock clock = new TestClock("2026-10-15T00:00:00Z");
            HeartbeatWriter engine = new HeartbeatWriter(dir, "engine", clock);
            engine.beat();
            Watchdog w = watchdog(clock);
            w.tick();
            assertThat(w.heartbeats().states()).containsEntry("engine", State.ALIVE);

            clock.advance(Duration.ofSeconds(20));
            free.set(10 * GIB);
            cpu = -1;
            // 자기 하트비트 파일 자리에 빈 폴더가 아닌 폴더를 두어 기록을 실패시킨다(실패 WARN이 이 점검에서 나간다)
            Files.delete(w.heartbeat().file());
            Files.createDirectories(w.heartbeat().file().resolve("block"));
            w.tick();
            assertThat(kinds()).containsExactlyInAnyOrder(EventRoutes.READING_MISSING, EventRoutes.PROCESS_STALE,
                    EventRoutes.STOP_REQUEST_ON);
            assertThat(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST)).exists();
            assertThat(dir.resolve(StopRequests.EXPERIMENT_STOP_REQUEST)).exists();
            assertThat(w.missingWarnings()).isEqualTo(1);
            w.close();
        } finally {
            pkg.removeHandler(throwing);
        }
    }

    /** 시험 본문. */
    interface Body {
        void run() throws Exception;
    }

    /** 감시·감시 에이전트 코어의 모든 로그 기록에 던지는 핸들러를 붙인 채 돌린다(PR #163 QA Q1). */
    private static void withThrowingLogs(Body body) throws Exception {
        java.util.logging.Logger pkg = java.util.logging.Logger.getLogger("com.autotrade");
        java.util.logging.Level oldLevel = pkg.getLevel();
        Handler throwing = new Handler() {
            @Override
            public void publish(LogRecord r) {
                throw new IllegalStateException("핸들러 예외 주입");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        throwing.setLevel(java.util.logging.Level.ALL);
        pkg.setLevel(java.util.logging.Level.ALL); // DEBUG 줄까지 핸들러에 닿게
        pkg.addHandler(throwing);
        try {
            body.run();
        } finally {
            pkg.removeHandler(throwing);
            pkg.setLevel(oldLevel);
        }
    }

    @Test
    void 감시_로그가_던져도_재기동_알림이_나간다() throws Exception {
        // PR #163 QA Q1(변이 QM2): 재기동 감지 로그가 던지면 시작 시각은 이미 바뀌어 재기동 알림이 영영 나가지 않았다
        withThrowingLogs(() -> {
            TestClock clock = new TestClock("2026-10-15T00:00:00Z");
            Watchdog w = watchdog(clock);
            new HeartbeatWriter(dir, "engine", clock).beat();
            w.tick();
            clock.advance(Duration.ofSeconds(3));
            new HeartbeatWriter(dir, "engine", clock).beat(); // 새 시작 시각
            w.tick();
            clock.advance(Duration.ofSeconds(3));
            w.tick();
            assertThat(sent).singleElement().satisfies(a -> {
                assertThat(a.kind()).isEqualTo(EventRoutes.PROCESS_RESTARTED);
                assertThat(a.subject()).isEqualTo("engine");
            });
            w.close();
        });
    }

    @Test
    void 감시_로그가_던져도_하트비트_읽기_실패와_회복_알림이_나간다() throws Exception {
        // PR #163 QA Q1(변이 QM3·QM4): UNREADABLE·ALIVE 바뀜 로그가 던지면 상태는 이미 바뀌어 그 알림이 영영 나가지 않았다
        withThrowingLogs(() -> {
            TestClock clock = new TestClock("2026-10-15T00:00:00Z");
            Watchdog w = watchdog(clock);
            HeartbeatWriter engine = new HeartbeatWriter(dir, "engine", clock);
            engine.beat();
            w.tick();
            Files.writeString(Heartbeat.file(dir, "engine"), "깨진 하트비트", StandardCharsets.UTF_8);
            clock.advance(Duration.ofSeconds(1));
            w.tick();
            assertThat(w.heartbeats().states()).containsEntry("engine", State.UNREADABLE);
            engine.beat();
            clock.advance(Duration.ofSeconds(1));
            w.tick();
            assertThat(kinds()).containsExactly(EventRoutes.HEARTBEAT_UNREADABLE, EventRoutes.PROCESS_RECOVERED);
            w.close();
        });
    }

    @Test
    void 감시_로그가_던져도_정지_요청_해제_알림이_나간다() throws Exception {
        // PR #163 QA Q1(변이 QM7): 해제 로그가 던지면 파일은 지워지고 꺼짐 상태가 되어 해제 알림이 영영 나가지 않았다
        withThrowingLogs(() -> {
            TestClock clock = new TestClock("2026-10-15T00:00:00Z");
            Watchdog w = watchdog(clock);
            free.set(10 * GIB);
            w.tick();
            free.set(100 * GIB);
            clock.advance(Duration.ofSeconds(5));
            w.tick();
            clock.advance(Duration.ofSeconds(60));
            w.tick();
            clock.advance(Duration.ofSeconds(5));
            w.tick();
            assertThat(w.stopRequests().active()).isFalse();
            assertThat(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST)).doesNotExist();
            assertThat(dir.resolve(StopRequests.EXPERIMENT_STOP_REQUEST)).doesNotExist();
            assertThat(kinds()).containsExactly(EventRoutes.STOP_REQUEST_ON, EventRoutes.STOP_REQUEST_OFF);
            w.close();
        });
    }

    @Test
    void 감시_로그가_던져도_한_정지_요청_파일_쓰기_실패가_다른_파일과_켜짐_알림을_막지_않는다() throws Exception {
        // PR #163 QA Q1(변이 QM1): 쓰기 실패 ERROR 로그가 던지면 남은 파일을 쓰지 않고 켜짐 알림도 건너뛰었다
        withThrowingLogs(() -> {
            TestClock clock = new TestClock("2026-10-15T00:00:00Z");
            Watchdog w = watchdog(clock);
            // 수집기 정지 요청 파일 자리에 빈 폴더가 아닌 폴더를 두어 쓰기를 실패시킨다(파일 순서상 먼저 쓴다)
            Files.createDirectories(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST).resolve("block"));
            free.set(10 * GIB);
            w.tick();
            assertThat(w.stopRequests().active()).isTrue();
            assertThat(dir.resolve(StopRequests.EXPERIMENT_STOP_REQUEST)).isRegularFile();
            assertThat(kinds()).containsExactly(EventRoutes.STOP_REQUEST_ON);
            w.close();
        });
    }

    @Test
    void 점검_단계가_던지고_그_실패_로그도_던져도_다음_단계로_넘어간다() throws Exception {
        // PR #163 QA Q1(변이 QM8): 단계 catch 안 로그가 던지면 그 점검의 남은 단계가 빠졌다. 점검의 모든 단계는 stage로 돈다
        withThrowingLogs(() -> {
            String value = Watchdog.stage(System.Logger.Level.WARNING, "주입 단계 실패", () -> {
                throw new IllegalStateException("단계 실패 주입");
            }, "대신 값");
            assertThat(value).isEqualTo("대신 값");
            boolean[] next = {false};
            Watchdog.stage(System.Logger.Level.ERROR, "주입 단계 실패", () -> {
                throw new IllegalStateException("단계 실패 주입");
            });
            Watchdog.stage(System.Logger.Level.ERROR, "다음 단계", () -> next[0] = true);
            assertThat(next[0]).isTrue();
        });
    }

    @Test
    void 점검_단계_실패는_단계_이름과_예외_종류로_남긴다() {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = capture(records);
        try {
            Watchdog.stage(System.Logger.Level.ERROR, "정지 요청 파일 처리 실패", () -> {
                throw new IllegalStateException("비밀이 섞일 수 있는 문구");
            });
        } finally {
            java.util.logging.Logger.getLogger(Watchdog.class.getName()).removeHandler(handler);
        }
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getLevel()).isEqualTo(java.util.logging.Level.SEVERE);
            assertThat(r.getMessage()).isEqualTo("정지 요청 파일 처리 실패 (IllegalStateException)");
        });
    }

    @Test
    void 회복을_알린_값이_다른_값의_경고_뒤_다시_빠지면_바로_알린다() {
        // PR #163 독립 QA R1(탐침 P3): cpu WARN → cpu 회복 INFO → 드라이브 WARN → 드라이브 회복·cpu 다시 빠짐이면
        // cpu는 간격 안에서 합친 경고 목록에 남아 있어 10분 동안 WARN·알림이 없었다(마지막 cpu 로그가 "다시 읽음")
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // WARN [cpu]
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // INFO [cpu]
        diskFails = true;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // WARN [disk]
        diskFails = false;
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // cpu 다시 빠짐: 바로 WARN
        assertThat(w.missingWarnings()).isEqualTo(3);
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING, EventRoutes.READING_RECOVERED,
                EventRoutes.READING_MISSING, EventRoutes.READING_MISSING);
        assertThat(sent.get(3).body()).startsWith("[cpu.system]");
        for (int i = 0; i < 10; i++) { // 계속 빠진 채로는 간격 안에서 다시 알리지 않는다
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.missingWarnings()).isEqualTo(3);
        w.close();
    }

    @Test
    void 큐가_억제한_결측_알림은_간격이_찬_반복_경고_때_한_번_다시_넣는다() {
        // PR #166 독립 QA R1(탐침 PB): cpu 결측 → cpu 회복 → 드라이브 결측 → 드라이브 회복·cpu 재결측이면 cpu 결측 알림은 큐가
        // 같은 대상·종류 10분 억제로 버린다. 예전에는 그 뒤 cpu가 계속 빠져도 반복 경고가 로그로만 나 채널에 cpu가 끝까지 오지 않았다
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        NotificationQueue queue = new NotificationQueue(Notifier.of(List.of(new LogChannel()), List.of(new LogChannel())),
                EventRoutes.defaults(), clock, ZoneOffset.UTC, "watchdog-test", NotificationQueue.DEFAULT_KAKAO_RECONSENT_HELP);
        Watchdog w = watchdog(clock, (kind, subject, title, body) -> {
            boolean accepted = queue.offer(kind, subject, title, body);
            if (accepted) {
                sent.add(new Sent(kind, subject, title, body));
            }
            return accepted;
        }, stage -> { });
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // cpu 결측 알림
        cpu = 0.1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 회복 알림
        diskFails = true;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 드라이브 결측 알림
        diskFails = false;
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // cpu 결측 알림은 큐가 억제
        assertThat(queue.suppressedTotal()).isEqualTo(1);
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING, EventRoutes.READING_RECOVERED,
                EventRoutes.READING_MISSING);
        assertThat(sent.get(2).body()).doesNotContain("cpu.system");

        clock.advance(Watchdog.MISSING_REPEAT.minusSeconds(5));
        w.tick(); // 간격 안: 아무것도 없음
        assertThat(sent).hasSize(3);
        clock.advance(Duration.ofSeconds(5));
        w.tick(); // 간격이 찬 반복 경고: 못 보낸 결측 알림을 지금 빠진 값으로 다시 넣는다
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING, EventRoutes.READING_RECOVERED,
                EventRoutes.READING_MISSING, EventRoutes.READING_MISSING);
        assertThat(sent.get(3).body()).startsWith("[cpu.system]");

        clock.advance(Watchdog.MISSING_REPEAT);
        w.tick(); // 받은 뒤의 반복 경고는 다시 로그로만(D-096)
        assertThat(sent).hasSize(4);
        assertThat(w.missingWarnings()).isEqualTo(5);
        queue.close(Duration.ZERO);
        w.close();
    }

    @Test
    void 큐가_받은_결측_알림은_반복_경고_때_다시_넣지_않는다() {
        // R1 고침이 D-096(반복 경고는 로그로)을 깨지 않는지: 받은 알림은 다시 넣지 않는다
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        for (int i = 0; i < 3; i++) {
            clock.advance(Watchdog.MISSING_REPEAT);
            w.tick();
        }
        assertThat(w.missingWarnings()).isEqualTo(3);
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING);
        w.close();
    }

    @Test
    void 결측_알림을_넣다_던지면_다음_반복_경고_때_다시_넣는다() {
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        boolean[] broken = {true};
        Watchdog w = watchdog(clock, (kind, subject, title, body) -> {
            if (broken[0]) {
                throw new IllegalStateException("알림 큐 실패 주입");
            }
            return sent.add(new Sent(kind, subject, title, body));
        }, stage -> { });
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(sent).isEmpty();
        broken[0] = false;
        clock.advance(Watchdog.MISSING_REPEAT);
        w.tick();
        assertThat(kinds()).containsExactly(EventRoutes.READING_MISSING);
        w.close();
    }

    @Test
    void 드라이브를_읽지_못하는_점검에서도_엔진_멈춤_알림이_나간다() throws Exception {
        // PR #166 독립 QA Q1(변이 V2): 자원 판정이 UNKNOWN이어도 하트비트 단계는 따로 돈다(장애표 #8 감지가 자원 읽기에 묶이지 않게)
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        HeartbeatWriter engine = new HeartbeatWriter(dir, "engine", clock);
        engine.beat();
        Watchdog w = watchdog(clock);
        w.tick();
        diskFails = true;
        clock.advance(Duration.ofMinutes(5)); // 엔진 하트비트가 끊김
        w.tick();
        assertThat(w.stopRequests().active()).isFalse();
        assertThat(kinds()).containsExactlyInAnyOrder(EventRoutes.READING_MISSING, EventRoutes.PROCESS_STALE);
        assertThat(sent).filteredOn(a -> a.kind().equals(EventRoutes.PROCESS_STALE)).singleElement()
                .extracting(Sent::subject).isEqualTo("engine");
        w.close();
    }

    /** 감시 로거 기록 중 단계 실패 줄만. */
    private static List<LogRecord> stageFailures(List<LogRecord> records) {
        return records.stream().filter(r -> r.getMessage().endsWith("(IllegalStateException)")).toList();
    }

    /** 엔진이 멈추고 드라이브 여유가 위험인 점검을, 한 단계를 실패시켜 돌린다. 기록된 로그를 돌려준다. */
    private List<LogRecord> tickWithFailingStage(Watchdog.Stage stage) throws IOException {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = capture(records);
        try {
            TestClock clock = new TestClock("2026-10-15T00:00:00Z");
            new HeartbeatWriter(dir, "engine", clock).beat();
            clock.advance(Duration.ofMinutes(5));
            free.set(10 * GIB);
            Watchdog w = failingAt(clock, new Watchdog.Stage[] {stage});
            w.tick();
            w.close();
        } finally {
            java.util.logging.Logger.getLogger(Watchdog.class.getName()).removeHandler(handler);
        }
        return records;
    }

    private static void assertStageFailure(List<LogRecord> records, java.util.logging.Level level, String message) {
        assertThat(stageFailures(records)).singleElement().satisfies(r -> {
            assertThat(r.getLevel()).isEqualTo(level);
            assertThat(r.getMessage()).isEqualTo(message);
        });
    }

    // PR #166 독립 QA Q2(변이 V1·V6)·architect N3: tick이 넘기는 단계 실패 로그 수준·문구와, 한 단계가 실패해도 나머지 단계가 도는지를
    // tick 경로로 고정한다. 문구는 D-110 결정 2대로 예전 catch와 같다

    @Test
    void 자기_하트비트_단계가_실패해도_나머지_단계는_돈다() throws Exception {
        List<LogRecord> records = tickWithFailingStage(Watchdog.Stage.SELF_HEARTBEAT);
        assertStageFailure(records, java.util.logging.Level.WARNING, "자기 하트비트 기록 실패 (IllegalStateException)");
        assertThat(Heartbeat.file(dir, Watchdog.PROCESS)).doesNotExist();
        assertThat(kinds()).containsExactly(EventRoutes.PROCESS_STALE, EventRoutes.STOP_REQUEST_ON);
    }

    @Test
    void 자원_단계가_실패하면_판단_못_함이라_정지_요청을_새로_만들지_않고_하트비트_단계는_돈다() throws Exception {
        List<LogRecord> records = tickWithFailingStage(Watchdog.Stage.RESOURCES);
        assertStageFailure(records, java.util.logging.Level.WARNING, "자원 점검 실패 (IllegalStateException)");
        assertThat(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST)).doesNotExist();
        assertThat(kinds()).containsExactly(EventRoutes.PROCESS_STALE);
    }

    @Test
    void 자원_단계가_실패하는_동안_켜진_정지_요청은_풀지_않는다() {
        // risk-ops PR #166 K2: 자원 단계의 대신 값은 UNKNOWN(판단 못 함)이라 해제 대기 시간이 지나도 정지 요청을 유지한다
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog.Stage[] failing = {null};
        Watchdog w = failingAt(clock, failing);
        free.set(10 * GIB);
        w.tick();
        assertThat(w.stopRequests().active()).isTrue();
        failing[0] = Watchdog.Stage.RESOURCES;
        free.set(100 * GIB);
        for (int i = 0; i < 30; i++) {
            clock.advance(Duration.ofSeconds(5));
            w.tick();
        }
        assertThat(w.stopRequests().active()).isTrue();
        assertThat(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST)).exists();
        w.close();
    }

    @Test
    void 정지_요청_사유를_만들다_던져도_위험_판정은_남아_정지_요청을_만든다() throws Exception {
        // PR #166 독립 QA R2: 판정과 사유를 한 람다로 묶으면 사유가 던질 때 CRITICAL이 UNKNOWN이 되어 정지 요청을 못 만들었다
        List<LogRecord> records = tickWithFailingStage(Watchdog.Stage.DETAIL);
        assertStageFailure(records, java.util.logging.Level.WARNING, "정지 요청 사유 작성 실패 (IllegalStateException)");
        assertThat(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST)).exists();
        assertThat(dir.resolve(StopRequests.EXPERIMENT_STOP_REQUEST)).exists();
        assertThat(kinds()).containsExactly(EventRoutes.PROCESS_STALE, EventRoutes.STOP_REQUEST_ON);
        assertThat(sent.get(1).body()).startsWith("사유를 만들지 못함. ");
    }

    @Test
    void 하트비트_단계가_실패해도_정지_요청_단계는_돈다() throws Exception {
        List<LogRecord> records = tickWithFailingStage(Watchdog.Stage.HEARTBEATS);
        assertStageFailure(records, java.util.logging.Level.WARNING, "하트비트 점검 실패 (IllegalStateException)");
        assertThat(kinds()).containsExactly(EventRoutes.STOP_REQUEST_ON);
    }

    @Test
    void 정지_요청_단계_실패는_ERROR로_남기고_앞_단계_알림은_나간다() throws Exception {
        List<LogRecord> records = tickWithFailingStage(Watchdog.Stage.STOP_REQUESTS);
        assertStageFailure(records, java.util.logging.Level.SEVERE, "정지 요청 파일 처리 실패 (IllegalStateException)");
        assertThat(dir.resolve(StopRequests.COLLECTOR_STOP_REQUEST)).doesNotExist();
        assertThat(kinds()).containsExactly(EventRoutes.PROCESS_STALE);
    }

    /** 감시 로거 기록을 모은다. 시험이 끝나면 떼야 한다. */
    private static Handler capture(List<LogRecord> records) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        java.util.logging.Logger.getLogger(Watchdog.class.getName()).addHandler(handler);
        return handler;
    }

    @Test
    void 반복_간격은_10분이다() {
        // D-096·ADR-011·runbook이 운영자에게 약속한 값(PR #123 QA Q2)
        assertThat(Watchdog.MISSING_REPEAT).isEqualTo(Duration.ofMinutes(10));
        assertThat(HeartbeatWatch.ALL_ABSENT_REPEAT).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 간격이_찬_경고_뒤에_다시_빠진_값은_바로_알린다() {
        // PR #123 QA Q1(변이 W5): 0분 {CPU, 드라이브} 경고 → 10분 {CPU}만 반복 경고 → 11분 드라이브가 다시 빠지면 바로 WARN
        TestClock clock = new TestClock("2026-10-15T00:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        diskFails = true;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(1);
        diskFails = false;
        clock.advance(Watchdog.MISSING_REPEAT);
        w.tick(); // CPU만 빠진 채 간격이 차서 반복 경고. 경고 값 집합은 {CPU}로 줄어야 한다
        assertThat(w.missingWarnings()).isEqualTo(2);
        diskFails = true;
        clock.advance(Duration.ofMinutes(1));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(3);
        w.close();
    }

    @Test
    void 시계가_뒤로_가도_반복_경고가_밀리지_않는다() {
        // PR #123 QA R4: 역행 폭만큼 반복 경고가 늦어지지 않게 역행이면 간격이 찬 것으로 본다
        TestClock clock = new TestClock("2026-10-15T01:00:00Z");
        Watchdog w = watchdog(clock);
        w.tick();
        cpu = -1;
        clock.advance(Duration.ofSeconds(5));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(-30));
        w.tick();
        assertThat(w.missingWarnings()).isEqualTo(2);
        w.close();
    }

    @Test
    void 빠진_값_경고는_WARNING_회복은_INFO로_남긴다() {
        // PR #123 QA Q2(변이 W1·W10): 운영자가 보는 것은 로그 수준이다
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger(Watchdog.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        jul.addHandler(handler);
        try {
            TestClock clock = new TestClock("2026-10-15T00:00:00Z");
            Watchdog w = watchdog(clock);
            w.tick();
            cpu = -1;
            clock.advance(Duration.ofSeconds(5));
            w.tick();
            cpu = 0.1;
            clock.advance(Duration.ofSeconds(5));
            w.tick();
            w.close();
            assertThat(records).filteredOn(r -> r.getMessage().startsWith("자원 값을 읽지 못함"))
                    .singleElement().extracting(LogRecord::getLevel).isEqualTo(java.util.logging.Level.WARNING);
            assertThat(records).filteredOn(r -> r.getMessage().startsWith("자원 값을 다시 읽음"))
                    .singleElement().extracting(LogRecord::getLevel).isEqualTo(java.util.logging.Level.INFO);
        } finally {
            jul.removeHandler(handler);
        }
    }

    @Test
    void 하트비트를_하나도_못_본_경고도_시계가_뒤로_가면_바로_되풀이한다() {
        TestClock clock = new TestClock("2026-10-15T01:00:00Z");
        HeartbeatWatch watch = new HeartbeatWatch(dir, List.of("engine"), Duration.ofSeconds(15));
        watch.check(clock.instant());
        assertThat(watch.allAbsentWarnings()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(1));
        watch.check(clock.instant());
        assertThat(watch.allAbsentWarnings()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(-30));
        watch.check(clock.instant());
        assertThat(watch.allAbsentWarnings()).isEqualTo(2);
    }
}
