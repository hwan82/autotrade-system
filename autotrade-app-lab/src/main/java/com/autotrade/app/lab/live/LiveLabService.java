package com.autotrade.app.lab.live;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.sim.live.LivePlan;
import com.autotrade.sim.live.LiveSession;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.SimulationReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 실험실의 실시간 모의 운용(ADR-010 2.3~2.4, D-076). 게이트웨이 시세 전달 소켓에서 받은 이벤트를 실시간 모의 세션 하나에 넘긴다.
 *
 * <ul>
 *   <li><b>실험실이 먼저 양보한다</b>(절대 규칙 6, SIM-14). 받는 쪽 스레드는 크기 제한 큐({@link #INBOX_CAPACITY})에 넣기만 하고, 차면
 *       버린 뒤 그 종목의 다음 이벤트 앞에 공백({@value LiveSession#INBOX_SOURCE}, CONSUMER_OVERFLOW)을 넣는다. 게이트웨이는 받는 쪽마다
 *       큐가 따로라 이쪽이 느려도 녹화와 다른 받는 쪽에 영향이 없다.</li>
 *   <li>감시 프로세스의 정지 요청 파일({@value #STOP_REQUEST})이 있으면 세션을 멈춘다(기록을 닫고 연결을 끊음). 장 시간 보호(D-063)는
 *       적용하지 않는다: 실시간 모의는 장중에 도는 것이 목적이고, 증권사를 직접 부르지 않으며 이벤트 하나씩 처리하는 가벼운 일이다.
 *       무거운 리플레이 확인은 실행 명령({@code runLiveReplayCheck})으로 장 밖에서 한다.</li>
 *   <li>세션은 한 번에 하나다. 실험실이 다시 뜨면 자동으로 다시 시작하지 않는다(화면에서 다시 시작하면 입력 기록으로 계좌를 되살린다).</li>
 * </ul>
 */
public class LiveLabService implements AutoCloseable {

    /** 감시가 만드는 정지 요청 파일(실험 실행 명령과 같은 위치, D-058·D-063). */
    public static final String STOP_REQUEST = "experiment-state/stop-request";
    public static final int INBOX_CAPACITY = 100_000;
    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Logger log = LoggerFactory.getLogger(LiveLabService.class);

    private final Path experimentsRoot;
    private final Path repoRoot;
    private final Path sessionsRoot;
    private final Path feedSocket;
    private final Path stopRequest;
    private final CodeVersion code;
    private final Clock clock;
    private Running running;
    private String lastStopReason = "";

    /**
     * @param experimentsRoot 등록 파일과 결과가 있는 {@code experiments/}
     * @param repoRoot {@code strategies/}가 있는 저장소 루트
     * @param sessionsRoot 세션 폴더(설정 폴더 아래 {@code live-sessions}, 저장소 밖)
     * @param feedSocket 게이트웨이 시세 전달 소켓
     * @param stopRequest 감시의 정지 요청 파일
     */
    public LiveLabService(Path experimentsRoot, Path repoRoot, Path sessionsRoot, Path feedSocket, Path stopRequest,
            CodeVersion code, Clock clock) {
        this.experimentsRoot = experimentsRoot.toAbsolutePath().normalize();
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.sessionsRoot = sessionsRoot.toAbsolutePath().normalize();
        this.feedSocket = Objects.requireNonNull(feedSocket, "feedSocket");
        this.stopRequest = Objects.requireNonNull(stopRequest, "stopRequest");
        this.code = Objects.requireNonNull(code, "code");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 등록 파일 {@code experiments/<id>/experiment.yaml}(input: live)로 세션을 시작한다. 입력 기록이 있으면 계좌를 되살린다.
     *
     * @throws IllegalStateException 이미 세션이 돌고 있거나 감시가 정지를 요청한 상태
     * @throws IllegalArgumentException 등록 파일이 없거나 형식이 틀림
     */
    public synchronized LiveSession.Status start(String id) {
        if (running != null) {
            throw new IllegalStateException("이미 실시간 모의 세션이 돌고 있습니다: " + running.session.status().experimentId());
        }
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("실험 ID 형식이 아닙니다");
        }
        Optional<String> stop = stopReason();
        if (stop.isPresent()) {
            throw new IllegalStateException(stop.get());
        }
        Path dir = experimentsRoot.resolve(id).normalize();
        Path file = dir.resolve("experiment.yaml");
        if (!dir.getParent().equals(experimentsRoot) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("등록 파일이 없습니다: experiments/" + id + "/experiment.yaml");
        }
        LivePlan plan;
        try {
            plan = LivePlan.parse(Files.readString(file, StandardCharsets.UTF_8), "experiments/" + id + "/experiment.yaml");
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 읽을 수 없습니다", e);
        }
        if (!plan.id().value().equals(id)) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다");
        }
        LiveSession session = LiveSession.open(plan, repoRoot, sessionsRoot, clock, m -> log.info("실시간 모의: {}", m));
        Running r = new Running(session, plan);
        r.client = new FeedClient(feedSocket, java.util.Set.of(plan.market()), "lab", r::offer, clock);
        r.pump = Thread.ofPlatform().name("live-pump").daemon(true).priority(Thread.MIN_PRIORITY).unstarted(r::pump);
        running = r;
        lastStopReason = "";
        r.pump.start();
        r.client.start();
        log.info("실시간 모의 세션 시작: {} (계좌 {}개)", id, plan.profiles().size());
        return session.status();
    }

    /** 세션을 멈춘다(입력 기록을 닫음). 다시 시작하면 기록으로 계좌를 되살린다. 돌고 있지 않으면 아무것도 하지 않는다. */
    public synchronized void stop(String reason) {
        if (running == null) {
            return;
        }
        Running r = running;
        running = null;
        r.halt();
        r.session.close();
        lastStopReason = reason;
        log.info("실시간 모의 세션 멈춤: {} ({})", r.plan.id(), reason);
    }

    /**
     * 세션을 끝낸다: 마지막 거래일을 마감하고 상세 보고서를 {@code experiments/<id>/live-detail.txt}에 쓴다. 이 뒤로 같은 세션은
     * 이어 쓰지 않는다(리플레이 확인 {@code runLiveReplayCheck}으로 넘어감).
     */
    public synchronized Finished finish() {
        if (running == null) {
            throw new IllegalStateException("돌고 있는 실시간 모의 세션이 없습니다");
        }
        Running r = running;
        running = null;
        r.halt();
        SimulationReport report = r.session.finish(code);
        Path target = experimentsRoot.resolve(r.plan.id().value()).resolve(LiveSession.LIVE_DETAIL_FILE);
        try {
            Files.writeString(target, ReportWriter.render(report), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("실시간 보고서를 쓸 수 없습니다: " + target, e);
        }
        lastStopReason = "끝냄";
        log.info("실시간 모의 세션 끝: {} → {}", r.plan.id(), target);
        long failed = report.runs().stream().filter(x -> !x.summary().failureReason().isEmpty()).count();
        return new Finished(r.plan.id().value(), "experiments/" + r.plan.id() + "/" + LiveSession.LIVE_DETAIL_FILE,
                report.runs().size(), (int) failed);
    }

    /** 세션을 끝낸 결과. */
    public record Finished(String experimentId, String detailFile, int accounts, int failedAccounts) {}

    /** 화면용 상태. 돌고 있는 세션이 없으면 session은 비어 있다. */
    public synchronized View status() {
        if (running == null) {
            return new View(Optional.empty(), ConnectionState.CLOSED, "", 0, 0, 0, lastStopReason);
        }
        Running r = running;
        return new View(Optional.of(r.session.status()), r.client.state(), r.client.lastError(),
                r.client.reconnectCount(), r.inbox.size(), r.inboxDropped.get(), r.session.status().stopReason());
    }

    /**
     * @param feedState 게이트웨이 시세 전달 연결 상태
     * @param inboxQueued 받는 큐에 남은 수
     * @param inboxDropped 받는 큐가 차서 버린 수(공백으로 남음)
     * @param stopReason 마지막 멈춘 이유(감시 정지 요청, 기록 실패 등)
     */
    public record View(Optional<LiveSession.Status> session, ConnectionState feedState, String feedError,
            long feedReconnects, int inboxQueued, long inboxDropped, String stopReason) {}

    Optional<String> stopReason() {
        if (!Files.notExists(stopRequest)) { // 있는지 판단할 수 없으면 멈추는 쪽
            return Optional.of("감시 프로세스가 자원 부족으로 정지를 요청했습니다(" + stopRequest.getFileName()
                    + "). 감시가 요청을 거두면 다시 시작하세요");
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        stop("실험실 종료");
    }

    /** 돌고 있는 세션 하나와 그 받는 쪽·처리 스레드. */
    private final class Running {
        private final LiveSession session;
        private final LivePlan plan;
        private final BlockingQueue<Item> inbox = new ArrayBlockingQueue<>(INBOX_CAPACITY);
        /** 받는 큐가 차서 버리기 시작한 시각(종목별). 받는 쪽 스레드에서만 쓴다. */
        private final Map<Symbol, Instant> overflowSince = new HashMap<>();
        private final AtomicLong inboxDropped = new AtomicLong();
        private FeedClient client;
        private Thread pump;
        private volatile boolean halted;

        Running(LiveSession session, LivePlan plan) {
            this.session = session;
            this.plan = plan;
        }

        /** 받는 쪽 스레드: 넣기만 하고 기다리지 않는다. */
        void offer(String sourceId, MarketEvent e) {
            Instant since = overflowSince.get(e.symbol());
            if (since != null) {
                DataGap gap = new DataGap(e.symbol(), 0, e.receivedAt(), e.receivedAt(), since, e.receivedAt(),
                        DataGap.GapReason.CONSUMER_OVERFLOW);
                if (!inbox.offer(new Item(LiveSession.INBOX_SOURCE, gap))) {
                    inboxDropped.incrementAndGet();
                    return;
                }
                overflowSince.remove(e.symbol());
            }
            if (!inbox.offer(new Item(sourceId, e))) {
                inboxDropped.incrementAndGet();
                overflowSince.putIfAbsent(e.symbol(), e.receivedAt());
            }
        }

        /** 처리 스레드: 큐에서 꺼내 세션에 준다. 1초마다 기록을 내리고 감시 정지 요청을 본다. */
        void pump() {
            long lastCheck = System.nanoTime();
            while (!halted) {
                Item item;
                try {
                    item = inbox.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (item != null && session.accept(item.sourceId(), item.event()) == LiveSession.Accepted.STOPPED
                        && !session.status().stopReason().isEmpty()) {
                    stopFromPump(session.status().stopReason());
                    return;
                }
                if (System.nanoTime() - lastCheck >= TimeUnit.SECONDS.toNanos(1)) {
                    lastCheck = System.nanoTime();
                    session.flush();
                    Optional<String> stop = stopReason();
                    if (stop.isPresent()) {
                        stopFromPump(stop.get());
                        return;
                    }
                }
            }
        }

        private void stopFromPump(String reason) {
            Thread.ofPlatform().daemon(true).start(() -> {
                synchronized (LiveLabService.this) {
                    if (running == this) {
                        stop(reason);
                    }
                }
            });
        }

        /** 받는 쪽과 처리 스레드를 멈춘다. 큐에 남은 이벤트는 버린다(기록에 없으므로 다시 시작해도 어긋나지 않는다). */
        void halt() {
            halted = true;
            client.close();
            // 처리 스레드는 끼어들기(interrupt)로 멈추지 않는다: 기록 파일 쓰기 중에 끼어들면 파일 채널이 닫혀 기록이 깨진다.
            // 큐를 0.5초마다 보므로 halted를 보고 스스로 끝난다
            if (Thread.currentThread() != pump) {
                try {
                    pump.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record Item(String sourceId, MarketEvent event) {}
}
