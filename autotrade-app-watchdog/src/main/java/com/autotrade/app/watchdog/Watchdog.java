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
import java.time.Duration;
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
 * 감시 루프(MON-02 1차, D-092·D-095). 점검마다 ① 자기 하트비트 기록 ② 드라이브 여유·PC CPU·메모리 수집과 임계 판정(감시 에이전트 코어) ③ 상주 프로세스
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
    /** 점검마다 있어야 하는 값(드라이브 경로마다 여유, PC CPU·메모리). 하나라도 빠지면 판단 못 함. */
    private final Set<String> requiredReadings = new TreeSet<>();
    /**
     * 빠진 필수 값 경고를 되풀이하는 간격(D-096). 값이 소리 없이 계속 빠지면 위험 판정도 꺼지므로(PR #122 risk-ops R1) 빠진 동안
     * 이 간격마다 다시 알린다(PR #122 QA R1). 같은 값이 빠졌다 돌아오기를 되풀이해도 이 간격 안에서는 다시 알리지 않는다(QA R4).
     */
    static final Duration MISSING_REPEAT = Duration.ofMinutes(10);

    /** 마지막 경고 뒤 경고한 값들. 여기 없는 값이 새로 빠지면 간격과 상관없이 바로 알린다. */
    private Set<String> warnedReadings = Set.of();
    private Instant lastMissingWarning;
    private boolean warnedSinceRecovery;
    private int missingWarnings;
    private int missingRecoveries;
    private ScheduledExecutorService scheduler;

    Watchdog(Path configDir, WatchdogSettings settings, Clock clock, DiskFreeSource.FreeSpace freeSpace, OsReadings os) {
        this.settings = settings;
        this.clock = clock;
        this.heartbeat = new HeartbeatWriter(configDir, PROCESS, clock);
        for (Path d : settings.disks()) {
            requiredReadings.add(DiskFreeSource.readingName(d));
        }
        requiredReadings.add(OsMetricsSource.CPU);
        requiredReadings.add(OsMetricsSource.MEMORY);
        Threshold disk = new Threshold(DiskFreeSource.METRIC, Threshold.Direction.BELOW, settings.diskWarnGb(),
                settings.diskCriticalGb(), settings.diskSustain());
        Threshold cpu = new Threshold(OsMetricsSource.CPU, Threshold.Direction.ABOVE, settings.cpu().warn(),
                settings.cpu().critical(), settings.cpu().sustain());
        Threshold memory = new Threshold(OsMetricsSource.MEMORY, Threshold.Direction.ABOVE, settings.memory().warn(),
                settings.memory().critical(), settings.memory().sustain());
        this.agent = new MonitorAgent(clock, List.of(new DiskFreeSource(settings.disks(), freeSpace), new OsMetricsSource(os)),
                List.of(disk, cpu, memory), 1, 50);
        this.heartbeats = new HeartbeatWatch(configDir, settings.processes(), settings.heartbeatStale());
        this.stopRequests = new StopRequests(configDir, settings.releaseHold());
        stopRequests.adoptLeftovers(clock.instant());
    }

    /** 점검 한 번. 점검 스레드가 부르며, 시험에서는 직접 부른다. */
    void tick() {
        Instant now = clock.instant();
        heartbeat.beat();
        StopRequests.Pressure pressure = StopRequests.Pressure.UNKNOWN;
        String detail = "자원 값을 읽지 못함";
        try {
            MetricSnapshot snapshot = agent.sampleOnce();
            reportMissing(snapshot, now);
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
        for (String name : requiredReadings) {
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

    /**
     * 빠진 필수 값을 알린다(D-096). 새 값이 빠지면 바로, 같은 값이 계속(또는 되풀이해) 빠지면 {@link #MISSING_REPEAT}마다 WARN.
     * 경고한 뒤 모두 돌아오면 INFO 한 번.
     */
    private void reportMissing(MetricSnapshot snapshot, Instant now) {
        Set<String> missing = new TreeSet<>(requiredReadings);
        for (Reading r : snapshot.readings()) {
            missing.remove(r.name());
        }
        if (missing.isEmpty()) {
            if (warnedSinceRecovery) {
                warnedSinceRecovery = false;
                missingRecoveries++;
                LOG.log(Logger.Level.INFO, "자원 값을 다시 읽음: " + warnedReadings);
            }
            return;
        }
        boolean newName = !warnedReadings.containsAll(missing);
        boolean due = lastMissingWarning == null || !now.isBefore(lastMissingWarning.plus(MISSING_REPEAT));
        if (!newName && !due) {
            return;
        }
        Set<String> warned = new TreeSet<>(missing);
        if (!due) {
            warned.addAll(warnedReadings); // 간격 안에서는 경고한 값을 잊지 않아야 오가는 값이 매 점검 경고를 내지 않는다
        }
        warnedReadings = Set.copyOf(warned);
        lastMissingWarning = now;
        warnedSinceRecovery = true;
        missingWarnings++;
        LOG.log(Logger.Level.WARNING, "자원 값을 읽지 못함: " + missing + ". 그 값의 위험 판정을 못 하므로 정지 요청을 새로 만들지 못하고, "
                + "켜진 정지 요청은 풀지 않는다(D-092 3번, D-095). 빠진 동안 " + MISSING_REPEAT.toMinutes() + "분마다 다시 알린다(D-096)");
    }

    /** 빠진 필수 값으로 WARN을 남긴 횟수(시험용). */
    int missingWarnings() {
        return missingWarnings;
    }

    /** 경고 뒤 모두 돌아와 INFO를 남긴 횟수(시험용). */
    int missingRecoveries() {
        return missingRecoveries;
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
