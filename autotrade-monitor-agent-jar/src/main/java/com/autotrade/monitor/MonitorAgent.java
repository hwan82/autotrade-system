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
 * 수집원 상태를 되돌려 다음 주기에 같은 줄을 다시 낸다(PR #119 QA Q1). 수집원 id는 겹치면 안 된다.
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
    private ScheduledExecutorService scheduler;

    /**
     * @param historySize 들고 있을 최근 스냅샷 수(1 이상)
     * @param eventHistorySize 들고 있을 최근 임계 이벤트 수(1 이상)
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
                if (line != null) {
                    sourceLogs.add(line);
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
            LOG.log(e.escalation() ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                    "임계 " + e.from() + " → " + e.to() + ": " + e.reading() + " = " + e.value() + e.unit()
                            + " (경계 " + e.limit() + e.unit() + ")");
        }
        for (MonitorListener l : listeners) {
            try {
                l.onSample(snapshot, fired);
            } catch (Throwable e) {
                LOG.log(System.Logger.Level.WARNING, "감시 리스너 실패 (" + e.getClass().getSimpleName() + ")");
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
            // 예약 작업이 예외·Error로 끝나면 다음 주기가 조용히 취소된다. 어떤 Throwable도 밖으로 내보내지 않는다
            LOG.log(System.Logger.Level.WARNING, "감시 수집 실패 (" + e.getClass().getSimpleName() + ")");
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
                    "수집원 회복: " + id + " (" + before.error + " " + before.count + "회 연속 실패 뒤)", id, before, null);
        }
        if (before == null) {
            Failure now = new Failure(error);
            failing.put(id, now);
            return new LogLine(System.Logger.Level.WARNING,
                    "수집원 실패: " + id + " (" + error + "). 같은 실패가 이어지는 동안은 다시 경고하지 않는다", id, null, now);
        }
        if (!before.error.equals(error)) {
            Failure now = new Failure(error);
            failing.put(id, now);
            return new LogLine(System.Logger.Level.WARNING,
                    "수집원 실패 바뀜: " + id + " (" + before.error + " → " + error + ")", id, before, now);
        }
        before.count++;
        if (!LOG.isLoggable(System.Logger.Level.DEBUG)) {
            return null; // DEBUG가 꺼져 있으면 주기마다 문자열을 만들지 않는다(architect PR #119 A2)
        }
        return new LogLine(System.Logger.Level.DEBUG, "수집원 실패 계속: " + id + " (" + error + ", " + before.count + "회째)",
                id, null, null);
    }

    /**
     * 수집원 로그를 낸다. WARN·INFO 줄을 내지 못하면 그 수집원 상태를 줄을 만들기 전으로 되돌려, 다음 주기에 같은 줄을 다시 내게
     * 한다(PR #119 QA Q1: 첫 WARN이 출력되지 못한 채 "이미 경고함"으로 남지 않게). 나머지 줄도 마저 내 본 뒤 첫 예외를 다시
     * 던진다(그 주기는 예전처럼 실패로 끝난다). DEBUG 반복 줄은 되돌리지 않는다(횟수만 센다).
     */
    private void writeSourceLogs(List<LogLine> lines) {
        Throwable first = null;
        for (LogLine line : lines) {
            try {
                LOG.log(line.level(), line.text());
            } catch (Throwable e) {
                if (line.level() != System.Logger.Level.DEBUG) {
                    synchronized (lock) {
                        // 그 사이 다른 호출이 상태를 또 바꿨으면 그대로 둔다
                        if (failing.get(line.id()) == line.installed()) {
                            if (line.previous() == null) {
                                failing.remove(line.id());
                            } else {
                                failing.put(line.id(), line.previous());
                            }
                        }
                    }
                }
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first instanceof RuntimeException e) {
            throw e;
        }
        if (first instanceof Error e) {
            throw e;
        }
        if (first != null) {
            throw new IllegalStateException(first);
        }
    }

    /**
     * 낼 로그 한 줄과, 출력에 실패했을 때 되돌릴 상태. {@code previous}는 줄을 만들기 전 상태(없으면 null), {@code installed}는
     * 줄을 만들며 넣은 상태(지웠으면 null).
     */
    private record LogLine(System.Logger.Level level, String text, String id, Failure previous, Failure installed) {}

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
