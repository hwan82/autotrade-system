package com.autotrade.app.lab.monitor;

import com.autotrade.monitor.Level;
import com.autotrade.monitor.MetricSnapshot;
import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitor.Reading;
import com.autotrade.monitor.Threshold;
import com.autotrade.monitor.ThresholdEvent;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실험실 모니터링(MON-05, UI-05 모니터링): 감시 에이전트를 실험실 프로세스 안에서 돌리고, 화면에 현재 값·최근 기록·임계
 * 이벤트를 주며 새 표본을 SSE로 밀어 준다.
 *
 * <p>보는 범위: PC 전체(CPU, 메모리, 디스크, 네트워크)와 <b>실험실 JVM</b>(힙, GC, 스레드). 실전 엔진·게이트웨이·감시 JVM의
 * 지표는 보지 않는다(다른 프로세스). 실험실은 자원 부족 때 먼저 멈추는 프로세스라 수집은 낮은 우선순위 데몬 스레드 하나다.
 * 이 서비스는 무엇도 멈추지 않는다: 임계 이벤트는 화면과 로그에만 간다.
 */
public final class MonitorService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);
    /** SSE 연결 하나의 최대 수명. 끝나면 브라우저 EventSource가 다시 붙는다. */
    static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    /** 동시에 붙을 수 있는 SSE 연결 수(창을 많이 열어도 스레드·연결이 끝없이 늘지 않게). */
    static final int MAX_STREAMS = 8;

    /** 수집 멈춤으로 보는 기준: 마지막 표본이 수집 주기의 이 배수보다 오래됨(risk-ops PR #114 R2). */
    static final int STALE_INTERVALS = 3;

    private final MonitorSettings settings;
    private final MonitorAgent agent;
    private final Clock clock;
    private final String problem;
    private final List<SseEmitter> streams = new CopyOnWriteArrayList<>();
    /**
     * SSE 보내기 전용 스레드. 수집 스레드에서 바로 보내면 느린 브라우저 하나가 수집을 늦춘다(risk-ops PR #114 K1). 대기는 2개까지만
     * 두고 넘치면 가장 오래된 것을 버린다(화면은 다음 표본으로 따라잡는다).
     */
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().name("monitor-sse").daemon(true).factory(),
            new ThreadPoolExecutor.DiscardOldestPolicy());

    /**
     * @param agent 꺼져 있거나 만들지 못했으면 null
     * @param problem 켜져 있는데 만들지 못한 이유(화면 표시), 아니면 null
     */
    public MonitorService(MonitorSettings settings, MonitorAgent agent, Clock clock, String problem) {
        this.settings = settings;
        this.agent = agent;
        this.clock = clock;
        this.problem = problem;
        if (agent != null) {
            agent.addListener(this::push);
        }
    }

    /** 수집 시작(켜져 있을 때만). */
    public void start() {
        if (agent != null) {
            agent.start(Duration.ofSeconds(settings.intervalSeconds()));
            log.info("모니터링 수집 시작: {}초 주기, 임계 {}개", settings.intervalSeconds(), agent.thresholds().size());
        } else {
            log.info(problem == null ? "모니터링 꺼짐(autotrade.lab.monitor.enabled=false)" : "모니터링 사용 불가");
        }
    }

    /** 화면 첫 그리기용 전체 상태. */
    public View view() {
        if (agent == null) {
            return new View(false, problem, settings.intervalSeconds(), settings.historySize(), false, null, List.of(),
                    List.of(), List.of());
        }
        List<MetricSnapshot> history = agent.history();
        Optional<MetricSnapshot> latest = history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
        return new View(true, null, settings.intervalSeconds(), settings.historySize(), stale(latest.map(MetricSnapshot::at)),
                latest.map(MonitorService::sample).orElse(null),
                agent.thresholds().stream().map(ThresholdView::of).toList(),
                history.stream().map(MonitorService::point).toList(), agent.recentEvents());
    }

    /** 마지막 표본이 주기의 {@link #STALE_INTERVALS}배보다 오래됐는지. 첫 수집 전은 멈춤이 아니다. */
    boolean stale(Optional<Instant> last) {
        return last.map(at -> Duration.between(at, clock.instant())
                .compareTo(Duration.ofSeconds((long) settings.intervalSeconds() * STALE_INTERVALS)) > 0).orElse(false);
    }

    /** 새 표본을 받을 SSE 연결. 꺼져 있거나 연결이 너무 많으면 빈 값. */
    public Optional<SseEmitter> openStream() {
        SseEmitter emitter;
        // 상한 검사와 추가를 한 번에(동시 요청이 함께 상한을 넘지 않게, architect PR #114 A2)
        synchronized (streams) {
            if (agent == null || streams.size() >= MAX_STREAMS) {
                return Optional.empty();
            }
            emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
            streams.add(emitter);
        }
        emitter.onCompletion(() -> streams.remove(emitter));
        emitter.onTimeout(() -> streams.remove(emitter));
        emitter.onError(e -> streams.remove(emitter));
        return Optional.of(emitter);
    }

    int openStreams() {
        return streams.size();
    }

    private void push(MetricSnapshot snapshot, List<ThresholdEvent> events) {
        if (streams.isEmpty()) {
            return;
        }
        Update update = new Update(sample(snapshot), point(snapshot), events);
        sender.execute(() -> send(update));
    }

    private void send(Update update) {
        for (SseEmitter emitter : streams) {
            try {
                emitter.send(SseEmitter.event().name("sample").data(update));
            } catch (IOException | IllegalStateException e) {
                // 브라우저가 떠났다. 다음 표본부터 보내지 않는다
                streams.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    @Override
    public void close() {
        if (agent != null) {
            agent.close();
        }
        sender.shutdownNow();
        streams.forEach(SseEmitter::complete);
        streams.clear();
    }

    static Sample sample(MetricSnapshot s) {
        List<ReadingView> readings = new ArrayList<>();
        for (Reading r : s.readings()) {
            String target = r.name().length() > r.metric().length() ? r.name().substring(r.metric().length() + 1) : "";
            readings.add(new ReadingView(r.name(), r.metric(), target, round(r.value()), r.unit(), s.levels().get(r.name())));
        }
        return new Sample(s.at(), readings, s.failedSources());
    }

    static Point point(MetricSnapshot s) {
        Map<String, Double> values = new LinkedHashMap<>();
        s.readings().forEach(r -> values.put(r.name(), round(r.value())));
        return new Point(s.at(), values);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /**
     * 화면 첫 상태. {@code latest}는 아직 첫 수집 전이면 null. {@code problem}은 켜져 있는데 수집원을 만들지 못한 이유(그때
     * {@code enabled=false}). {@code stale}은 마지막 표본이 수집 주기의 3배보다 오래됨(수집 멈춤).
     */
    public record View(boolean enabled, String problem, int intervalSeconds, int historySize, boolean stale, Sample latest, List<ThresholdView> thresholds,
            List<Point> history, List<ThresholdEvent> events) {}

    /** SSE {@code sample} 이벤트 본문. */
    public record Update(Sample latest, Point point, List<ThresholdEvent> events) {}

    /** 한 표본의 지표들. {@code level}은 임계가 있는 지표만. */
    public record Sample(Instant at, List<ReadingView> readings, List<String> failedSources) {}

    public record ReadingView(String name, String metric, String target, double value, String unit, Level level) {}

    /** 그래프 점 하나: 지표 이름과 대상 → 값. */
    public record Point(Instant at, Map<String, Double> values) {}

    public record ThresholdView(String metric, String direction, double warn, double critical, int sustain) {
        static ThresholdView of(Threshold t) {
            return new ThresholdView(t.metric(), t.direction().name(), t.warn(), t.critical(), t.sustain());
        }
    }
}
