package com.autotrade.app.watchdog;

import com.autotrade.monitor.HeartbeatWriter;
import com.autotrade.monitor.Level;
import com.autotrade.monitor.MetricSnapshot;
import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitor.Reading;
import com.autotrade.monitor.Threshold;
import java.lang.System.Logger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 감시 루프(MON-02 1차, D-092). 점검마다 ① 자기 하트비트 기록 ② 드라이브 여유 수집·임계 판정(감시 에이전트 코어) ③ 상주 프로세스
 * 하트비트 판정 ④ 정지 요청 파일 켜기·갱신·끄기를 한다.
 *
 * <p>점검은 스레드 하나에서 고정 간격으로 돈다. 한 점검의 어느 단계가 던져도 그 단계만 버리고 다음 단계와 다음 점검은 계속한다
 * (예약 작업 밖으로 아무것도 내보내지 않음, D-090과 같은 원칙). 자기 하트비트를 점검 루프 안에서 쓰므로 하트비트가 멈추면 루프가
 * 멈춘 것이다.
 */
final class Watchdog implements AutoCloseable {

    static final String PROCESS = "watchdog";

    private static final Logger LOG = System.getLogger(Watchdog.class.getName());

    private final WatchdogSettings settings;
    private final Clock clock;
    private final HeartbeatWriter heartbeat;
    private final MonitorAgent agent;
    private final HeartbeatWatch heartbeats;
    private final StopRequests stopRequests;
    private final Set<String> diskReadings = new TreeSet<>();
    private ScheduledExecutorService scheduler;

    Watchdog(Path configDir, WatchdogSettings settings, Clock clock, DiskFreeSource.FreeSpace freeSpace) {
        this.settings = settings;
        this.clock = clock;
        this.heartbeat = new HeartbeatWriter(configDir, PROCESS, clock);
        for (Path d : settings.disks()) {
            diskReadings.add(DiskFreeSource.readingName(d));
        }
        Threshold disk = new Threshold(DiskFreeSource.METRIC, Threshold.Direction.BELOW, settings.diskWarnGb(),
                settings.diskCriticalGb(), settings.diskSustain());
        this.agent = new MonitorAgent(clock, List.of(new DiskFreeSource(settings.disks(), freeSpace)), List.of(disk), 1, 50);
        this.heartbeats = new HeartbeatWatch(configDir, settings.processes(), settings.heartbeatStale());
        this.stopRequests = new StopRequests(configDir, settings.releaseHold());
        stopRequests.adoptLeftovers(clock.instant());
    }

    /** 점검 한 번. 점검 스레드가 부르며, 시험에서는 직접 부른다. */
    void tick() {
        Instant now = clock.instant();
        heartbeat.beat();
        StopRequests.Pressure pressure = StopRequests.Pressure.UNKNOWN;
        String detail = "드라이브 여유를 읽지 못함";
        try {
            MetricSnapshot snapshot = agent.sampleOnce();
            pressure = pressure(snapshot);
            detail = detail(snapshot);
        } catch (Throwable e) {
            LOG.log(Logger.Level.WARNING, "자원 점검 실패 (" + e.getClass().getSimpleName() + ")");
        }
        try {
            heartbeats.check(now);
        } catch (Throwable e) {
            LOG.log(Logger.Level.WARNING, "하트비트 점검 실패 (" + e.getClass().getSimpleName() + ")");
        }
        try {
            stopRequests.update(now, pressure, detail);
        } catch (Throwable e) {
            LOG.log(Logger.Level.ERROR, "정지 요청 파일 처리 실패 (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 위험 값이 하나라도 있으면 위험. 그 밖에는 값이 빠지면 판단 못 함, 경고가 있으면 경고, 모두 정상이면 정상. */
    StopRequests.Pressure pressure(MetricSnapshot snapshot) {
        Map<String, Level> levels = snapshot.levels();
        Set<String> present = new TreeSet<>();
        for (Reading r : snapshot.readings()) {
            present.add(r.name());
        }
        boolean warn = false;
        boolean missing = false;
        for (String name : diskReadings) {
            if (!present.contains(name)) {
                missing = true;
                continue;
            }
            Level level = levels.getOrDefault(name, Level.OK);
            if (level == Level.CRITICAL) {
                return StopRequests.Pressure.CRITICAL;
            }
            warn |= level == Level.WARN;
        }
        if (missing) {
            return StopRequests.Pressure.UNKNOWN;
        }
        return warn ? StopRequests.Pressure.ELEVATED : StopRequests.Pressure.CLEAR;
    }

    private static String detail(MetricSnapshot snapshot) {
        List<String> parts = new ArrayList<>();
        for (Reading r : snapshot.readings()) {
            Level level = snapshot.levels().getOrDefault(r.name(), Level.OK);
            if (level != Level.OK) {
                parts.add(r.name() + " " + Math.round(r.value()) + r.unit() + " " + level);
            }
        }
        return parts.isEmpty() ? "정상" : String.join(", ", parts);
    }

    /** 점검을 시작한다(첫 점검은 바로). */
    synchronized void start() {
        if (scheduler != null) {
            throw new IllegalStateException("이미 시작했습니다");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("watchdog").factory());
        scheduler.scheduleWithFixedDelay(this::tickQuietly, 0, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (Throwable e) {
            // 예약 작업이 Throwable로 끝나면 다음 점검이 조용히 취소된다
        }
    }

    HeartbeatWatch heartbeats() {
        return heartbeats;
    }

    StopRequests stopRequests() {
        return stopRequests;
    }

    HeartbeatWriter heartbeat() {
        return heartbeat;
    }

    /** 점검을 멈추고 자기 하트비트 파일을 지운다. 정지 요청 파일은 그대로 둔다(다음 감시가 이어받음). */
    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        heartbeat.close();
        agent.close();
    }
}
