package com.autotrade.app.lab.experiment;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실험 진행 상태 SSE(UI-01·UI-02, D-093). 실행 명령은 다른 프로세스라 실험실에 알려 주지 않는다. 그래서 연결이 하나라도 열려
 * 있을 때만 {@link #INTERVAL}마다 작업 폴더를 다시 읽고({@link ExperimentJobs#list()}), 화면에 줄 첫 페이지가 바뀌었을 때만
 * {@code jobs} 이벤트를 보낸다. 실행 중인 명령은 5초마다 상태 파일을 고쳐 쓰므로 실행 중에는 그 주기로 이벤트가 간다.
 *
 * <p>읽기와 보내기는 낮은 우선순위 데몬 스레드 하나에서 한다(실험실은 자원 부족 때 먼저 멈추는 프로세스, SIM-14). 바뀐 것이
 * 없어도 {@link #KEEPALIVE}마다 주석 줄을 보내 떠난 브라우저의 연결을 치운다(연결 상한을 떠난 연결이 차지하지 않게). 이 클래스는
 * 상태 파일을 읽기만 하고 아무것도 쓰거나 멈추지 않는다. 취소는 {@link ExperimentJobs#cancel(String)} 그대로다.
 */
public final class ExperimentJobStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExperimentJobStream.class);

    /** 작업 폴더를 다시 읽는 주기. 실행 명령의 상태 파일 갱신 주기(5초)보다 짧게. */
    static final Duration INTERVAL = Duration.ofSeconds(2);
    /** 보낼 것이 없을 때 주석 줄을 보내는 주기. */
    static final Duration KEEPALIVE = Duration.ofSeconds(15);
    /** SSE 연결 하나의 최대 수명. 끝나면 브라우저 EventSource가 다시 붙는다. */
    static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    /** 동시에 붙을 수 있는 연결 수. */
    static final int MAX_STREAMS = 8;
    /** 이벤트 하나에 담는 작업 수(목록 API 첫 페이지 기본 크기와 같음). */
    static final int PAGE_SIZE = 50;

    private final ExperimentJobs jobs;
    private final Clock clock;
    private final List<SseEmitter> streams = new CopyOnWriteArrayList<>();
    /** 첫 이벤트를 아직 받지 못한 연결. 다음 읽기에서 바뀌지 않았어도 보낸다. */
    private final List<SseEmitter> fresh = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService executor;
    private Snapshot last;
    private Instant lastSent = Instant.MIN;
    private volatile boolean closing;

    public ExperimentJobStream(ExperimentJobs jobs, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 읽기 스레드를 띄운다. 연결이 없으면 주기마다 깨기만 하고 파일을 읽지 않는다. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("experiment-jobs-sse").daemon(true)
                .priority(Thread.MIN_PRIORITY).factory());
        executor.scheduleWithFixedDelay(this::tickSafely, INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 새 연결. 종료 중이거나 연결이 너무 많으면 빈 값. 첫 이벤트는 곧바로 한 번 읽어 보낸다. */
    public Optional<SseEmitter> open() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        return register(emitter) ? Optional.of(emitter) : Optional.empty();
    }

    /** 연결을 받는다(상한 검사와 추가를 한 번에, 동시 요청이 함께 상한을 넘지 않게). */
    boolean register(SseEmitter emitter) {
        synchronized (streams) {
            if (closing || streams.size() >= MAX_STREAMS) {
                return false;
            }
            streams.add(emitter);
            fresh.add(emitter);
        }
        Runnable remove = () -> forget(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        // 첫 화면이 다음 주기까지 기다리지 않게 바로 한 번 읽는다
        ScheduledExecutorService ex = executor;
        if (ex != null) {
            try {
                ex.execute(this::tickSafely);
            } catch (RuntimeException e) {
                // 종료 중. 연결은 closeStreams가 닫는다
            }
        }
        return true;
    }

    int openStreams() {
        return streams.size();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            // 작업 폴더를 못 읽어도 스레드는 살아 있어야 한다. 화면은 다음 주기에 다시 받는다
            log.debug("실험 작업 상태를 읽지 못했습니다: {}", e.toString());
        }
    }

    /** 한 주기: 연결이 있으면 읽고, 바뀌었거나 새 연결이면 보내고, 오래 조용했으면 주석 줄을 보낸다. 읽기 스레드에서만 부른다. */
    synchronized void tick() {
        if (streams.isEmpty()) {
            last = null; // 다음 연결은 처음부터
            return;
        }
        Snapshot now = Snapshot.of(jobs.list());
        Instant at = clock.instant();
        if (!now.equals(last)) {
            last = now;
            fresh.clear();
            for (SseEmitter emitter : streams) {
                send(emitter, SseEmitter.event().name("jobs").data(now));
            }
            lastSent = at;
            return;
        }
        for (SseEmitter emitter : List.copyOf(fresh)) {
            fresh.remove(emitter);
            send(emitter, SseEmitter.event().name("jobs").data(now));
        }
        if (!at.isBefore(lastSent.plus(KEEPALIVE))) {
            for (SseEmitter emitter : streams) {
                send(emitter, SseEmitter.event().comment("keepalive"));
            }
            lastSent = at;
        }
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // 브라우저가 떠났다
            forget(emitter);
            emitter.completeWithError(e);
        }
    }

    private void forget(SseEmitter emitter) {
        streams.remove(emitter);
        fresh.remove(emitter);
    }

    /** 열린 연결을 모두 끝내고 새 연결을 받지 않는다(종료 첫 단계, 웹 서버 graceful shutdown보다 먼저). */
    public void closeStreams() {
        synchronized (streams) {
            closing = true;
        }
        List<SseEmitter> open = List.copyOf(streams);
        streams.clear();
        fresh.clear();
        open.forEach(SseEmitter::complete);
    }

    @Override
    public void close() {
        closeStreams();
        ScheduledExecutorService ex = executor;
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    /**
     * {@code jobs} 이벤트 본문. 목록 API 첫 페이지({@code page=0&size=50})와 같은 모양이다: {@code items}는 갱신 시각
     * 내림차순 최대 {@value #PAGE_SIZE}개, {@code total}은 읽은 작업 수, {@code unreadable}·{@code truncated}는 목록과 같다.
     */
    public record Snapshot(List<ExperimentJobs.Job> items, int page, int size, int total, int unreadable,
            boolean truncated) {
        static Snapshot of(ExperimentJobs.Listing listing) {
            List<ExperimentJobs.Job> all = listing.items();
            return new Snapshot(List.copyOf(all.subList(0, Math.min(PAGE_SIZE, all.size()))), 0, PAGE_SIZE, all.size(),
                    listing.unreadable(), listing.truncated());
        }
    }
}
