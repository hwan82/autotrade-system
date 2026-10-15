package com.autotrade.monitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 하트비트 파일을 주기마다 덮어쓴다(MON-02, D-092). 임시 파일에 쓴 뒤 옮겨서 읽는 쪽이 반쯤 쓴 내용을 보지 않게 한다.
 *
 * <p>쓰기 실패는 그 주기만 버리고 다음 주기에 다시 쓴다. 이 기록기는 붙은 프로세스를 멈추게 하지 않는다: 어떤 {@code Throwable}도
 * 밖으로 내보내지 않고, 실패 로그는 상태가 바뀔 때만 한 줄 남긴다(디스크가 차도 로그가 쌓이지 않게, PR #114 risk-ops K5).
 * 정상 종료({@link #close()})하면 파일을 지워서, 감시가 "꺼짐"(파일 없음)과 "멈춤"(오래된 파일)을 가른다.
 */
public final class HeartbeatWriter implements AutoCloseable {

    /** 상주 프로세스의 기본 하트비트 주기. 감시의 멈춤 기준(기본 15초)은 이 값의 세 배다. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);

    private static final System.Logger LOG = System.getLogger(HeartbeatWriter.class.getName());

    private final Path file;
    private final String process;
    private final long pid;
    private final Clock clock;
    private final Instant startedAt;
    private final Object lock = new Object();
    private long seq;
    private boolean failing;
    private boolean closed;
    private ScheduledExecutorService scheduler;

    /** 주기 기록 없이 만든다. {@link #beat()}를 직접 부르거나 {@link #start(Duration)}로 주기 기록을 시작한다. */
    public HeartbeatWriter(Path configDir, String process, Clock clock) {
        this.file = Heartbeat.file(configDir, process);
        this.process = process;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pid = ProcessHandle.current().pid();
        this.startedAt = clock.instant();
        // 이전 실행이 쓰다 죽어 남긴 임시 파일(<이름>.hb.<pid>.tmp)을 거둔다(PR #116 QA·architect N3). 같은 이름 프로세스는 하나만 돈다
        SmallFile.deleteLeftoverTemps(file);
    }

    /** 만들고 바로 주기 기록을 시작한다(첫 기록은 바로). */
    public static HeartbeatWriter start(Path configDir, String process, Clock clock, Duration interval) {
        HeartbeatWriter writer = new HeartbeatWriter(configDir, process, clock);
        writer.start(interval);
        return writer;
    }

    /** 주기 기록을 시작한다. 데몬 스레드 하나에서 쓴다. 두 번 부르면 거절한다. */
    public void start(Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("하트비트 주기는 0보다 커야 합니다");
        }
        synchronized (lock) {
            if (scheduler != null || closed) {
                throw new IllegalStateException("이미 시작했거나 닫았습니다");
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("heartbeat-" + process).daemon(true).factory());
            scheduler.scheduleWithFixedDelay(this::beatQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** 한 번 쓴다. 성공 여부를 돌려준다(예외를 던지지 않는다). 닫은 뒤에는 쓰지 않는다. */
    public boolean beat() {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            seq++;
            Heartbeat hb = new Heartbeat(process, pid, startedAt, clock.instant(), seq);
            try {
                AtomicFile.write(file, hb.format());
                if (failing) {
                    failing = false;
                    LOG.log(System.Logger.Level.INFO, "하트비트 기록 복구: " + file);
                }
                return true;
            } catch (IOException | RuntimeException e) {
                if (!failing) {
                    failing = true;
                    LOG.log(System.Logger.Level.WARNING,
                            "하트비트를 쓰지 못했습니다(복구될 때까지 다시 알리지 않음): " + file + " (" + e.getClass().getSimpleName() + ")");
                }
                return false;
            }
        }
    }

    private void beatQuietly() {
        try {
            beat();
        } catch (Throwable e) {
            // 예약 작업이 Throwable로 끝나면 다음 주기가 조용히 취소된다(D-090과 같은 원칙)
        }
    }

    public Path file() {
        return file;
    }

    /** 주기 기록을 멈추고 파일을 지운다(정상 종료 표시). 지우지 못해도 던지지 않는다. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "하트비트 파일을 지우지 못했습니다: " + file);
            }
        }
    }
}
