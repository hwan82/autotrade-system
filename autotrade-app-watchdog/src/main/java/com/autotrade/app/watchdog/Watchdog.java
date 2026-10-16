package com.autotrade.app.watchdog;

import com.autotrade.monitor.HeartbeatWriter;
import com.autotrade.monitor.Level;
import com.autotrade.monitor.MetricSnapshot;
import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitor.Reading;
import com.autotrade.monitor.Threshold;
import com.autotrade.notification.EventRoutes;
import com.autotrade.notification.NotificationQueue;
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
 * <p>상태가 바뀌면 알림도 넣는다(D-100): 프로세스 멈춤·읽기 실패·재기동(긴급)과 회복·정상 종료(정보), 정지 요청 켜짐(긴급)·꺼짐(정보),
 * 자원 값이 새로 빠짐(긴급)·다시 읽음(정보). 알림은 큐에 넣기만 하므로 점검을 막지 않는다. 빠진 값의 10분 반복 경고는 로그에만 남긴다.
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
    private final Alerts alerts;
    /** 닫을 때 함께 닫는 알림 큐(시험에서는 null). */
    private final NotificationQueue notifications;
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
        this(configDir, settings, clock, freeSpace, os, Alerts.NONE, null);
    }

    Watchdog(Path configDir, WatchdogSettings settings, Clock clock, DiskFreeSource.FreeSpace freeSpace, OsReadings os,
            Alerts alerts, NotificationQueue notifications) {
        this.alerts = alerts;
        this.notifications = notifications;
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
            for (HeartbeatWatch.Change c : heartbeats.check(now)) {
                alertChange(c);
            }
            for (HeartbeatWatch.Restart r : heartbeats.restarts()) {
                alert(EventRoutes.PROCESS_RESTARTED, r.process(), r.process() + " 재기동 감지",
                        "점검 사이에 프로세스가 죽었다 다시 떴습니다(이전 시작 " + r.previousStart() + ", 새 시작 " + r.newStart() + ")."
                                + ("engine".equals(r.process()) ? " 장애표 #8: 대사 통과 전에는 실전 매매를 재개하지 마세요" : ""));
            }
        } catch (Throwable e) {
            LOG.log(Logger.Level.WARNING, "하트비트 점검 실패 (" + e.getClass().getSimpleName() + ")");
        }
        try {
            boolean before = stopRequests.active();
            stopRequests.update(now, pressure, detail);
            boolean after = stopRequests.active();
            if (!before && after) {
                alert(EventRoutes.STOP_REQUEST_ON, "stop-request", "자원 위험: 일괄 명령 정지 요청",
                        detail + ". 수집기·실험 명령에 정지 요청 파일을 만들었습니다(5.2 0번). 실전 엔진은 멈추지 않습니다");
            } else if (before && !after) {
                alert(EventRoutes.STOP_REQUEST_OFF, "stop-request", "정지 요청 해제",
                        "자원이 정상으로 " + settings.releaseHold().toSeconds() + "초 이어져 정지 요청 파일을 지웠습니다");
            }
        } catch (Throwable e) {
            LOG.log(Logger.Level.ERROR, "정지 요청 파일 처리 실패 (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 하트비트 상태 변화를 알림으로. 처음 본 꺼짐·살아 있음은 알리지 않는다(기동 직후 소음). */
    private void alertChange(HeartbeatWatch.Change c) {
        String p = c.process();
        switch (c.to()) {
            case STALE -> alert(EventRoutes.PROCESS_STALE, p, p + " 멈춤",
                    "마지막 하트비트 " + c.age().toSeconds() + "초 전. 비정상 종료나 멈춤"
                            + ("engine".equals(p) ? ". 장애표 #8: 재기동은 사람이 하고 대사 통과 후에만 재개" : ". 장애표 #9"));
            case UNREADABLE -> alert(EventRoutes.HEARTBEAT_UNREADABLE, p, p + " 하트비트 읽기 실패",
                    "하트비트 파일을 읽거나 해석하지 못해 살아 있는지 모릅니다");
            case ALIVE -> {
                if (c.from() == HeartbeatWatch.State.STALE || c.from() == HeartbeatWatch.State.UNREADABLE) {
                    alert(EventRoutes.PROCESS_RECOVERED, p, p + " 하트비트 회복", "이전 상태 " + c.from());
                }
            }
            case ABSENT -> {
                if (c.from() != null && c.from() != HeartbeatWatch.State.ABSENT) {
                    alert(EventRoutes.PROCESS_STOPPED, p, p + " 종료", "하트비트 파일이 지워졌습니다(정상 종료)");
                }
            }
        }
    }

    /** 알림을 넣는다. 무엇이 나도 점검을 멈추지 않는다. */
    private void alert(String kind, String subject, String title, String body) {
        try {
            alerts.send(kind, subject, title, body);
        } catch (Throwable e) {
            // 알림 큐는 던지지 않지만, 넣는 쪽도 막아 둔다
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
                alert(EventRoutes.READING_RECOVERED, "readings", "자원 값을 다시 읽음", String.valueOf(warnedReadings));
            }
            return;
        }
        boolean newName = !warnedReadings.containsAll(missing);
        // 시계가 뒤로 가면 간격이 찬 것으로 본다(PR #123 QA R4, HeartbeatWatch와 같이)
        boolean due = lastMissingWarning == null || now.isBefore(lastMissingWarning)
                || !now.isBefore(lastMissingWarning.plus(MISSING_REPEAT));
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
        if (newName) {
            // 알림은 새로 빠진 값만(10분 반복은 로그로). 같은 대상·종류는 알림 큐도 10분에 한 번만 보낸다
            alert(EventRoutes.READING_MISSING, "readings", "감시가 자원 값을 읽지 못함",
                    missing + ". 그 값으로는 정지 요청을 새로 만들지 못합니다(켜진 정지 요청은 유지)");
        }
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

    /** 운영 배선 시험용(PR #128 QA Q1). 알림 큐 없이 만든 감시는 null. */
    NotificationQueue notifications() {
        return notifications;
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
        if (notifications != null) {
            // 남은 알림은 최대 3초 보내 보고 나머지는 로그에만 남긴다
            notifications.close();
        }
    }
}
