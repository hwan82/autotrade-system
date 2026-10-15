package com.autotrade.monitor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private ScheduledExecutorService scheduler;

    /**
     * @param historySize 들고 있을 최근 스냅샷 수(1 이상)
     * @param eventHistorySize 들고 있을 최근 임계 이벤트 수(1 이상)
     */
    public MonitorAgent(Clock clock, List<MetricSource> sources, List<Threshold> thresholds, int historySize,
            int eventHistorySize) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sources = List.copyOf(sources);
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
        for (MetricSource source : sources) {
            try {
                readings.addAll(source.read());
            } catch (Throwable e) {
                // Error(LinkageError 등)도 그 수집원만 버린다. 코어는 JDK 허용 목록(D-086) 안 타입만 쓰므로 LinkageError·
                // VirtualMachineError를 이름으로 가르지 못한다. 감시는 계속 도는 쪽이 낫다(risk-ops PR #114 R2)
                failed.add(source.id());
                LOG.log(System.Logger.Level.WARNING, "수집원 실패: " + source.id() + " (" + e.getClass().getSimpleName() + ")");
            }
        }
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

    private static <T> void append(List<T> list, T item, int cap) {
        list.add(item);
        if (list.size() > cap) {
            list.subList(0, list.size() - cap).clear();
        }
    }
}
