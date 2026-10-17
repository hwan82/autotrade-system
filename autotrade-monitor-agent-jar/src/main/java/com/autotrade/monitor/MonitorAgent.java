package com.autotrade.monitor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 감시 에이전트: 주기마다 수집원을 읽어 스냅샷을 만들고, 임계를 판정하고, 최근 기록을 들고, 듣는 쪽에 알린다(MON-05).
 *
 * <p>수집은 낮은 우선순위의 데몬 스레드 하나에서 한다. 수집원이나 듣는 쪽이 던진 것은 {@code Error}까지 그 주기의 그
 * 부분만 버리고 다음 주기를 막지 않는다. 그래도 수집이 멈췄는지는 {@link #lastSampleAt()}으로 본다(주기의 몇 배 넘게
 * 그대로면 멈춤). 이 에이전트는 무엇도 멈추거나 죽이지 않는다: 임계 이벤트를 내기만 하고, 그에 따른 조치는 붙인 쪽이 정한다
 * (감시 프로세스는 정지 요청 파일만 만든다, D-058).
 *
 * <p>수집원 실패 로그는 상태가 바뀔 때만 WARN이다: 처음 실패, 실패 종류가 바뀜. 같은 실패가 이어지는 동안은 DEBUG, 다시
 * 읽히면 INFO 한 줄. OSHI가 안 되는 PC에서 매 주기 WARN이 쌓여 다른 경고를 묻지 않게 한다(risk-ops PR #114 K5). 주기마다의
 * 실패는 스냅샷의 {@link MetricSnapshot#failedSources()}에 그대로 남는다. WARN·INFO 줄을 내지 못하면(로그 핸들러 예외) 그
 * 줄을 들고 있다가 그 수집원에 새 WARN·INFO가 생기기 전까지 다음 주기마다 다시 낸다. 실패 상태(종류·연속 횟수)는 출력과
 * 상관없이 실제대로 센다(PR #119 QA Q1, PR #155 F1·N5). 그 주기의 스냅샷과 임계 판정, 듣는 쪽 알림은 로그 출력과 상관없이
 * 그대로 낸다: 어떤 로그 출력 실패도 수집을 끊지 않는다(PR #155 N4).
 *
 * <p>핸들러가 여럿이고 앞 핸들러는 출력한 뒤 뒤 핸들러가 던지면 출력됐는지 알 수 없어 같은 줄이 그 핸들러가 고쳐질 때까지 매 주기
 * 다시 나온다. 유실보다 반복을 고른 것이다: 첫 WARN이 빠지면 운영자가 실패를 모르고, JUL 핸들러·logback 어펜더는 출력 오류를
 * 스스로 삼켜 이 경우는 드물다(PR #155 QA R1).
 *
 * <p>수집원 id는 겹치면 안 된다.
 */
public final class MonitorAgent implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(MonitorAgent.class.getName());

    private final Clock clock;
    private final List<MetricSource> sources;
    private final ThresholdEvaluator evaluator;
    private final int historySize;
    private final int eventHistorySize;
    private final List<MonitorListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final List<MetricSnapshot> history = new ArrayList<>();
    private final List<ThresholdEvent> events = new ArrayList<>();
    /** 지금 실패 중인 수집원 id → 실패 상태. 수집 스레드만 바꾸지만 시험이 직접 부를 수 있어 {@link #lock} 안에서만 다룬다. */
    private final Map<String, Failure> failing = new HashMap<>();
    /** 내지 못한 WARN·INFO 줄(수집원 id마다 가장 최근 것 하나). {@link #lock} 안에서만 다룬다. */
    private final Map<String, LogLine> unsent = new HashMap<>();
    /** 수집원 id마다 가장 최근에 만든 WARN·INFO 줄 번호. 출력에 실패한 줄이 그 뒤 새 줄을 덮지 않게 한다. */
    private final Map<String, Long> latestLine = new HashMap<>();
    private long lineSeq;
    private ScheduledExecutorService scheduler;

    /**
     * @param historySize 들고 있을 최근 스냅샷 수(1 이상)
     * @param eventHistorySize 들고 있을 최근 임계 이벤트 수(1 이상)
     * @throws IllegalArgumentException 수집원 id가 겹치거나 기록 크기가 1보다 작을 때
     * @throws NullPointerException 수집원 id가 null일 때
     */
    public MonitorAgent(Clock clock, List<MetricSource> sources, List<Threshold> thresholds, int historySize,
            int eventHistorySize) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sources = List.copyOf(sources);
        // 같은 id가 둘이면 실패 상태가 섞여 주기마다 WARN·INFO가 번갈아 남는다(PR #119 QA R1)
        Set<String> ids = new HashSet<>();
        for (MetricSource source : this.sources) {
            if (!ids.add(Objects.requireNonNull(source.id(), "수집원 id"))) {
                throw new IllegalArgumentException("수집원 id가 겹칩니다: " + source.id());
            }
        }
        this.evaluator = new ThresholdEvaluator(thresholds);
        if (historySize < 1 || eventHistorySize < 1) {
            throw new IllegalArgumentException("기록 크기는 1 이상이어야 합니다");
        }
        this.historySize = historySize;
        this.eventHistorySize = eventHistorySize;
    }

    /** 한 주기 수집. 수집 스레드가 부르며, 시험에서는 직접 부른다. */
    public MetricSnapshot sampleOnce() {
        List<Reading> readings = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<LogLine> sourceLogs = new ArrayList<>();
        for (MetricSource source : sources) {
            String error = null;
            try {
                readings.addAll(source.read());
            } catch (Throwable e) {
                // Error(LinkageError 등)도 그 수집원만 버린다. 코어는 JDK 허용 목록(D-086) 안 타입만 쓰므로 LinkageError·
                // VirtualMachineError를 이름으로 가르지 못한다. 감시는 계속 도는 쪽이 낫다(risk-ops PR #114 R2)
                failed.add(source.id());
                error = e.getClass().getSimpleName();
            }
            synchronized (lock) {
                LogLine line = trackFailure(source.id(), error);
                if (line != null && line.level() != System.Logger.Level.DEBUG) {
                    // 새 상태 변화 줄이 내지 못한 이전 줄을 대신한다(최근 상태가 맞는 줄이다)
                    unsent.remove(source.id());
                    line = line.numbered(++lineSeq);
                    latestLine.put(source.id(), line.seq());
                    sourceLogs.add(line);
                } else {
                    LogLine retry = unsent.remove(source.id());
                    if (retry != null) {
                        sourceLogs.add(retry);
                    }
                    if (line != null) {
                        sourceLogs.add(line);
                    }
                }
            }
        }
        writeSourceLogs(sourceLogs);
        readings.sort(Comparator.comparing(Reading::name));
        MetricSnapshot snapshot;
        List<ThresholdEvent> fired;
        Instant at = clock.instant();
        synchronized (lock) {
            fired = evaluator.evaluate(at, readings);
            snapshot = new MetricSnapshot(at, readings, evaluator.levels(), failed);
            append(history, snapshot, historySize);
            for (ThresholdEvent e : fired) {
                append(events, e, eventHistorySize);
            }
        }
        for (ThresholdEvent e : fired) {
            // 이벤트는 recentEvents()와 듣는 쪽에 그대로 간다(PR #155 F1)
            logQuietly(e.escalation() ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                    "임계 " + e.from() + " → " + e.to() + ": " + e.reading() + " = " + e.value() + e.unit()
                            + " (경계 " + e.limit() + e.unit() + ")");
        }
        for (MonitorListener l : listeners) {
            try {
                l.onSample(snapshot, fired);
            } catch (Throwable e) {
                // 이 로그가 던져도 남은 듣는 쪽과 스냅샷 반환을 건너뛰지 않는다(architect PR #155 N4)
                logQuietly(System.Logger.Level.WARNING, "감시 리스너 실패 (" + e.getClass().getSimpleName() + ")");
            }
        }
        return snapshot;
    }

    /** 주기 수집을 시작한다. 첫 수집은 바로 한다. 두 번 부르면 거절한다. */
    public void start(Duration interval) {
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("수집 주기는 0보다 커야 합니다");
        }
        synchronized (lock) {
            if (scheduler != null) {
                throw new IllegalStateException("이미 시작했습니다");
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("monitor-agent").daemon(true).priority(Thread.MIN_PRIORITY).factory());
            scheduler.scheduleWithFixedDelay(this::sampleQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void sampleQuietly() {
        try {
            sampleOnce();
        } catch (Throwable e) {
            // 예약 작업이 예외·Error로 끝나면 다음 주기가 조용히 취소된다. 어떤 Throwable도 밖으로 내보내지 않는다.
            // 이 로그가 던져도 마찬가지다(architect PR #155 N4)
            logQuietly(System.Logger.Level.WARNING, "감시 수집 실패 (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** 로그 한 줄. 출력 실패(로그 핸들러 예외)는 삼킨다. 출력이 안 되는 로그로 그 실패를 알릴 수도 없다. */
    private static void logQuietly(System.Logger.Level level, String text) {
        try {
            LOG.log(level, text);
        } catch (Throwable ignored) {
            // 위 설명대로 삼킨다
        }
    }

    /** 듣는 쪽을 붙인다. 돌려준 객체를 닫으면 뗀다. */
    public AutoCloseable addListener(MonitorListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    /** 가장 최근 스냅샷(아직 없으면 빈 값). */
    public Optional<MetricSnapshot> latest() {
        synchronized (lock) {
            return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
        }
    }

    /** 마지막으로 수집을 끝낸 시각(아직 없으면 빈 값). 붙인 쪽이 수집 멈춤을 알아보는 근거다. */
    public Optional<Instant> lastSampleAt() {
        return latest().map(MetricSnapshot::at);
    }

    /** 최근 스냅샷(오래된 것부터). */
    public List<MetricSnapshot> history() {
        synchronized (lock) {
            return List.copyOf(history);
        }
    }

    /** 최근 임계 이벤트(오래된 것부터). */
    public List<ThresholdEvent> recentEvents() {
        synchronized (lock) {
            return List.copyOf(events);
        }
    }

    public List<Threshold> thresholds() {
        return evaluator.thresholds();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

    /**
     * 수집원 하나의 이번 주기 결과로 실패 상태를 갱신하고 남길 로그 한 줄을 돌려준다(없으면 null). {@code error}는 실패면 예외
     * 클래스 이름, 성공이면 null. 처음 실패·종류 바뀜은 WARN, 같은 실패 반복은 DEBUG(꺼져 있으면 null), 회복은 INFO다.
     */
    private LogLine trackFailure(String id, String error) {
        Failure before = failing.get(id);
        if (error == null) {
            if (before == null) {
                return null;
            }
            failing.remove(id);
            return new LogLine(System.Logger.Level.INFO,
                    "수집원 회복: " + id + " (" + before.error + " " + before.count + "회 연속 실패 뒤)", id, 0);
        }
        if (before == null) {
            Failure now = new Failure(error);
            failing.put(id, now);
            return new LogLine(System.Logger.Level.WARNING,
                    "수집원 실패: " + id + " (" + error + "). 같은 실패가 이어지는 동안은 다시 경고하지 않는다", id, 0);
        }
        if (!before.error.equals(error)) {
            Failure now = new Failure(error);
            failing.put(id, now);
            return new LogLine(System.Logger.Level.WARNING,
                    "수집원 실패 바뀜: " + id + " (" + before.error + " → " + error + ")", id, 0);
        }
        before.count++;
        if (!LOG.isLoggable(System.Logger.Level.DEBUG)) {
            return null; // DEBUG가 꺼져 있으면 주기마다 문자열을 만들지 않는다(architect PR #119 A2)
        }
        return new LogLine(System.Logger.Level.DEBUG, "수집원 실패 계속: " + id + " (" + error + ", " + before.count + "회째)",
                id, 0);
    }

    /**
     * 수집원 로그를 낸다. WARN·INFO 줄을 내지 못하면(로그 핸들러 예외) 그 줄을 들고 있다가 다음 주기에 다시 낸다(PR #119 QA Q1:
     * 첫 WARN이 출력되지 못한 채 사라지지 않게). 실패 상태는 되돌리지 않아 연속 횟수는 실제대로 센다(PR #155 N5·K3). 출력 실패는
     * 삼키고 그 주기의 스냅샷·임계 판정은 그대로 이어 간다(architect PR #155 F1). DEBUG 반복 줄은 다시 내지 않는다(횟수만 센다).
     */
    private void writeSourceLogs(List<LogLine> lines) {
        for (LogLine line : lines) {
            try {
                LOG.log(line.level(), line.text());
            } catch (Throwable e) {
                if (line.level() == System.Logger.Level.DEBUG) {
                    continue;
                }
                synchronized (lock) {
                    // 그 사이 다른 호출이 그 수집원의 새 줄을 만들었으면 이 줄은 낡았으므로 버린다(architect PR #155 N3)
                    Long latest = latestLine.get(line.id());
                    if (latest != null && latest == line.seq()) {
                        unsent.put(line.id(), line);
                    }
                }
            }
        }
    }

    /** 낼 로그 한 줄. {@code seq}는 WARN·INFO 줄에 매기는 번호(DEBUG는 0). */
    private record LogLine(System.Logger.Level level, String text, String id, long seq) {
        LogLine numbered(long n) {
            return new LogLine(level, text, id, n);
        }
    }

    /** 수집원 하나의 연속 실패 상태. */
    private static final class Failure {
        final String error;
        long count = 1;

        Failure(String error) {
            this.error = error;
        }
    }

    private static <T> void append(List<T> list, T item, int cap) {
        list.add(item);
        if (list.size() > cap) {
            list.subList(0, list.size() - cap).clear();
        }
    }
}
