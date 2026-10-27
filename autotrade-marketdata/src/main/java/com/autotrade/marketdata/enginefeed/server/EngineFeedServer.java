package com.autotrade.marketdata.enginefeed.server;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.EngineHello;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.EngineLine;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Heartbeat;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Interest;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.ProtocolException;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Rejection;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.SourceState;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Status;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Unserved;
import com.autotrade.marketdata.recording.RecordingCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 시세 게이트웨이 쪽 실전 엔진 전달 서버(ADR-016 2.2~2.5, D-143). 게이트웨이만 쓴다(ArchUnit).
 *
 * <p><b>게이트웨이를 막지 않는다</b>(절대 규칙 6). {@link #publish}는 수신 스레드에서 ① 소스별 마지막 시세 수신 시각을 적고
 * ② 관심 종목인지 불변 스냅샷으로 보고(잠금 없음) ③ 고정 크기 큐에 넣기만 한다. 기다리지 않고 예외를 던지지 않는다(안에서 난
 * 예외는 횟수만 센다, risk-ops PR #245 R5). 큐가 차면 그 줄을 버리고, 종목마다 미리 만든 칸에 처음 버린 시각만 적었다가 다음
 * 이벤트 앞에 {@link DataGap}(CONSUMER_OVERFLOW, 출처 {@value #SOURCE_ID})을 넣는다(버릴 때 객체를 만들지 않음).
 *
 * <p>받는 쪽은 하나다. 이미 붙어 있으면 새 연결은 인사 없이 닫고 ERROR 로그를 남긴다. 엔진 인사가
 * {@link #HELLO_TIMEOUT} 안에 오지 않으면 끊는다. 한 번의 쓰기(최대 {@value #BATCH_BYTES}바이트 묶음)가 {@link #STALL_LIMIT} 넘게 끝나지 않으면 끊는다.
 *
 * <p>상태 줄·생존 줄은 이벤트 줄과 같은 큐를 탄다(앞지르지 않음). 큐가 차서 생존 줄을 넣지 못하면 그 생존 줄은 버리고 다시
 * 넣지 않는다. 엔진은 생존 줄이 3초 없으면 전달 끊김으로 보호 폴링에 넘어간다(architect PR #245 N7). 상태 줄은 버리지 않고 다음
 * 생존 줄 차례에 그때의 상태로 다시 넣는다(상태 줄은 매번 전체 상태라 마지막 것만 있으면 된다).
 *
 * <p>관심 종목(전체 교체)은 같은 연결 안에서 {@code rev}가 늘어날 때만 받는다. 형식·상한이 틀리면 앞 관심을 그대로 두고
 * 상태 줄 {@code rejected}로 답한다. 받은 관심은 {@link InterestSink}(G2 구독 계획)에 넘긴다. 엔진이 끊겨도 마지막 관심은
 * {@link #INTEREST_RETENTION} 동안 유지하고, 그 안에 새 연결이 관심을 보내지 않으면 빈 관심을 넘긴다.
 *
 * <p>1초마다 소켓 파일이 이 서버가 만든 파일인지 본다. 지워졌으면 다시 만들고(붙어 있던 엔진 연결은 유지) ERROR 로그를 남긴다.
 * 다른 파일이 그 자리를 차지했으면 빼앗지 않고 ERROR 로그만 남긴다(ADR-016 2.2, QA PR #245 Q4).
 *
 * <p>모든 스레드는 데몬이다. 서버가 죽어도 게이트웨이의 수신·녹화·실험실 전달은 계속된다.
 */
public final class EngineFeedServer implements AutoCloseable {

    /** 큐 크기 `[가정]`(관심 128종목 × 초당 수십 건을 몇 초 버팀, ADR-016 2.5). */
    public static final int QUEUE_CAPACITY = 20_000;
    public static final Duration HELLO_TIMEOUT = Duration.ofSeconds(5);
    /** 한 줄 쓰기 멈춤 한도 `[가정]`(실험실 30초보다 짧게, ADR-016 2.5). */
    public static final Duration STALL_LIMIT = Duration.ofSeconds(10);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(1);
    /** 엔진이 끊긴 뒤 마지막 관심 유지 시간 `[가정]`(ADR-016 2.4). */
    public static final Duration INTEREST_RETENTION = Duration.ofSeconds(60);
    public static final Duration SOCKET_CHECK_INTERVAL = Duration.ofSeconds(1);
    /** 서버가 직접 만든 공백 줄의 출처 이름. */
    public static final String SOURCE_ID = "ENGINE-FEED";

    private static final Logger LOG = System.getLogger(EngineFeedServer.class.getName());
    private static final long LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** 한 번에 모아 쓰는 크기. 멈춤 판단은 이 묶음 쓰기 단위다. */
    static final int BATCH_BYTES = 64 * 1024;

    /**
     * 받은 관심 종목을 구독 계획에 넘기는 곳(G2 {@code SourcePlanner}). 서버의 연결 스레드나 타이머 스레드에서 불리며 빨리
     * 돌아와야 한다. 배정 결과는 {@link #assignment}로 알린다.
     */
    @FunctionalInterface
    public interface InterestSink {
        void apply(SortedSet<Symbol> protect, SortedSet<Symbol> entry);
    }

    /** 시험에서 바꾸는 시간·크기. */
    record Settings(int queueCapacity, Duration helloTimeout, Duration stallLimit, Duration heartbeatInterval,
            Duration retention, Duration socketCheckInterval) {
        static final Settings DEFAULT = new Settings(QUEUE_CAPACITY, HELLO_TIMEOUT, STALL_LIMIT, HEARTBEAT_INTERVAL,
                INTEREST_RETENTION, SOCKET_CHECK_INTERVAL);
    }

    private final Path socket;
    private final Clock clock;
    private final InterestSink sink;
    private final Settings settings;
    private final EngineFeedListener listening;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(daemon("engine-feed-timer", Thread.NORM_PRIORITY));
    private final Thread acceptor;

    /** 소스 이름 → 마지막 시세 수신 시각 칸. 칸은 처음 볼 때 한 번 만든다. */
    private final ConcurrentHashMap<String, RxCell> rx = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConnectionState> sourceStates = new ConcurrentHashMap<>();
    private final AtomicLong publishErrors = new AtomicLong();
    private final AtomicLong droppedTotal = new AtomicLong();

    /** 이벤트를 넘길 연결과 관심 종목 칸. 연결이 없거나 이 연결의 관심이 아직 없으면 null. */
    private volatile Route route;

    // ---- 아래는 this 잠금 아래에서만 쓴다 ----
    /** 지금 자리를 차지한 연결(인사 전 포함). */
    private Connection current;
    /** 구독 계획에 넘긴 마지막 관심(앞 연결 것일 수 있음). 없으면 null. */
    private Interest applied;
    /** 관심이 바뀔 때마다 는다. 유지 시간 만료 작업이 그 사이 새 관심이 왔는지 본다. */
    private long interestGeneration;
    private SortedMap<Symbol, String> served = Collections.emptySortedMap();
    private SortedMap<Symbol, Unserved> unserved = Collections.emptySortedMap();
    // ---- 타이머 스레드에서만 쓴다 ----
    private long errorsLogged;
    private long droppedLogged;
    private long lastLogAt = System.nanoTime() - LOG_INTERVAL_NANOS;

    private volatile boolean closed;

    private EngineFeedServer(Path socket, Clock clock, InterestSink sink, Settings settings) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.settings = settings;
        this.listening = EngineFeedListener.open(socket);
        this.acceptor = daemon("engine-feed-accept", Thread.NORM_PRIORITY).newThread(this::acceptLoop);
        acceptor.start();
        long hb = settings.heartbeatInterval().toMillis();
        timer.scheduleAtFixedRate(this::tick, hb, hb, TimeUnit.MILLISECONDS);
        long check = settings.socketCheckInterval().toMillis();
        timer.scheduleWithFixedDelay(this::checkSocket, check, check, TimeUnit.MILLISECONDS);
        long stallCheck = Math.max(50, settings.stallLimit().toMillis() / 5);
        timer.scheduleWithFixedDelay(this::checkStall, stallCheck, stallCheck, TimeUnit.MILLISECONDS);
    }

    /**
     * 엔진 전달 소켓을 연다.
     *
     * @throws IOException 소켓을 열 수 없거나 다른 프로세스가 이미 그 경로에서 듣고 있을 때(게이트웨이는 ERROR 로그를 남기고
     *     녹화·실험실 전달은 그대로 한다, ADR-016 2.2)
     */
    public static EngineFeedServer open(Path socket, Clock clock, InterestSink sink) throws IOException {
        return new EngineFeedServer(socket, clock, sink, Settings.DEFAULT);
    }

    static EngineFeedServer open(Path socket, Clock clock, InterestSink sink, Settings settings) throws IOException {
        return new EngineFeedServer(socket, clock, sink, settings);
    }

    // ---- 게이트웨이 수신 스레드 ----

    /**
     * 게이트웨이가 받은 이벤트 하나를 넘긴다. 관심 종목 밖이어도 부른다(소스별 마지막 수신 시각에 쓴다). 기다리지 않고 예외를
     * 던지지 않는다.
     *
     * @param sourceId 원래 소스 이름(녹화의 {@code src}와 같다)
     */
    public void publish(String sourceId, MarketEvent event) {
        try {
            if (!(event instanceof DataGap)) {
                // 공백은 허브·서버가 만든 줄이라 시세 수신이 아니다(lastRx는 시세 메시지만, ADR-016 2.3)
                RxCell cell = rx.get(sourceId);
                if (cell == null) {
                    cell = rx.computeIfAbsent(sourceId, k -> new RxCell());
                }
                cell.at = event.receivedAt();
            }
            Route r = route;
            if (r == null) {
                return;
            }
            Slot slot = r.slots.get(event.symbol());
            if (slot != null) {
                r.connection.offer(sourceId, event, slot);
            }
        } catch (RuntimeException e) {
            publishErrors.incrementAndGet();
        }
    }

    /** 소스 연결 상태가 바뀌면 부른다. 생존·상태 줄의 {@code sources}에 들어간다. */
    public void sourceState(String sourceId, ConnectionState state) {
        try {
            ConnectionState before = sourceStates.put(Objects.requireNonNull(sourceId), Objects.requireNonNull(state));
            if (before != state) {
                synchronized (this) {
                    sendStatusLocked(null);
                }
            }
        } catch (RuntimeException e) {
            publishErrors.incrementAndGet();
        }
    }

    /**
     * 구독 계획의 배정 결과(G2). 관심 종목 중 어디에도 없는 종목은 {@link Unserved#PENDING}으로 알린다. 관심 종목의 배정이
     * 바뀌면 상태 줄을 보낸다.
     *
     * @param served 종목 → 담당 소스(등록 성공 응답이나 그 종목 이벤트를 받은 것만)
     */
    public void assignment(Map<Symbol, String> served, Map<Symbol, Unserved> unserved) {
        SortedMap<Symbol, String> s = Collections.unmodifiableSortedMap(new TreeMap<>(served));
        SortedMap<Symbol, Unserved> u = Collections.unmodifiableSortedMap(new TreeMap<>(unserved));
        synchronized (this) {
            Interest a = applied;
            boolean changed = a != null && !view(a, this.served, this.unserved).equals(view(a, s, u));
            this.served = s;
            this.unserved = u;
            if (changed) {
                sendStatusLocked(null);
            }
        }
    }

    /** 엔진이 인사를 마치고 붙어 있는지. */
    public synchronized boolean engineConnected() {
        return current != null && current.greeted;
    }

    /** 큐가 차서 버린 이벤트 수(모든 연결 합). */
    public long droppedCount() {
        return droppedTotal.get();
    }

    /** {@link #publish} 안에서 잡은 예외 수. */
    public long publishErrorCount() {
        return publishErrors.get();
    }

    public Path socket() {
        return socket;
    }

    // ---- 연결 받기 ----

    private void acceptLoop() {
        long failures = 0;
        while (!closed) {
            ServerSocketChannel server = listening.channel();
            SocketChannel ch;
            try {
                ch = server.accept();
                failures = 0;
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                if (listening.channel() != server) {
                    continue; // 소켓을 다시 만들어 바뀌었다
                }
                failures++;
                if ((failures & (failures - 1)) == 0) {
                    LOG.log(Level.WARNING, "엔진 시세 연결 받기 실패({0}번째): {1}", failures, e.getClass().getName());
                }
                try {
                    Thread.sleep(Math.min(1_000L, 10L << Math.min(failures - 1, 10)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            Connection c;
            synchronized (this) {
                if (current != null) {
                    LOG.log(Level.ERROR, "엔진 시세 소켓에 이미 받는 쪽이 붙어 있어 새 연결을 닫습니다(받는 쪽은 하나)");
                    closeQuietly(ch);
                    continue;
                }
                c = new Connection(ch);
                current = c;
            }
            c.reader = daemon("engine-feed-read", Thread.NORM_PRIORITY).newThread(c::read);
            c.reader.start();
        }
    }

    // ---- 관심 ----

    /** 연결의 읽기 스레드에서 부른다. */
    private void onInterest(Connection c, Interest i) {
        synchronized (this) {
            if (current != c || c.stopped) {
                return;
            }
            if (i.rev() <= c.rev) {
                return; // 줄어들거나 같은 rev는 무시(같은 연결 안에서만 비교)
            }
            c.rev = i.rev();
            applied = i;
            interestGeneration++;
            route = new Route(c, slots(i.all()));
        }
        try {
            sink.apply(i.protect(), i.entry());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "관심 종목 적용 중 오류: {0}", e.getClass().getName());
        }
        synchronized (this) {
            if (current == c) {
                sendStatusLocked(null);
            }
        }
    }

    private void onRejected(Connection c, Rejection why) {
        LOG.log(Level.ERROR, "엔진 관심 줄을 거부했습니다({0}). 앞 관심을 그대로 둡니다", why);
        synchronized (this) {
            if (current == c) {
                sendStatusLocked(why);
            }
        }
    }

    private static Map<Symbol, Slot> slots(Set<Symbol> symbols) {
        Map<Symbol, Slot> m = new HashMap<>();
        symbols.forEach(s -> m.put(s, new Slot()));
        return Map.copyOf(m);
    }

    /** 연결이 끝났다. 유지 시간 뒤에도 새 관심이 없으면 빈 관심을 넘긴다. */
    private void ended(Connection c) {
        long generation;
        synchronized (this) {
            if (current != c) {
                return;
            }
            current = null;
            if (route != null && route.connection == c) {
                route = null;
            }
            generation = interestGeneration;
            if (applied == null || closed) {
                return;
            }
        }
        LOG.log(Level.WARNING, "엔진 시세 연결이 끊겼습니다. 관심 종목을 {0}초 유지합니다", settings.retention().toSeconds());
        timer.schedule(() -> expire(generation), settings.retention().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void expire(long generation) {
        synchronized (this) {
            if (closed || interestGeneration != generation || applied == null) {
                return;
            }
            applied = null;
            interestGeneration++;
        }
        LOG.log(Level.WARNING, "엔진이 {0}초 안에 다시 붙지 않아 엔진 관심 종목을 비웁니다", settings.retention().toSeconds());
        try {
            sink.apply(Collections.emptySortedSet(), Collections.emptySortedSet());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "관심 종목 비우기 중 오류: {0}", e.getClass().getName());
        }
    }

    // ---- 상태·생존 줄 ----

    /** 지금 연결에 상태 줄을 넣는다. 큐가 차면 다음 생존 줄 차례에 다시 넣는다. */
    private void sendStatusLocked(Rejection rejected) {
        Connection c = current;
        if (c == null || !c.greeted) {
            return;
        }
        c.statusPending = false;
        String line = EngineFeedProtocol.statusLine(statusLocked(c, rejected));
        if (!c.queue.offer(Item.control(line))) {
            c.statusPending = true;
        }
    }

    private Status statusLocked(Connection c, Rejection rejected) {
        View v = applied == null ? View.EMPTY : view(applied, served, unserved);
        return new Status(c.rev, clock.instant(), sources(), v.served, v.unserved, rejected);
    }

    private static View view(Interest i, SortedMap<Symbol, String> served, SortedMap<Symbol, Unserved> unserved) {
        SortedMap<Symbol, String> s = new TreeMap<>();
        SortedMap<Symbol, Unserved> u = new TreeMap<>();
        for (Symbol sym : i.all()) {
            String src = served.get(sym);
            if (src != null) {
                s.put(sym, src);
            } else {
                u.put(sym, unserved.getOrDefault(sym, Unserved.PENDING));
            }
        }
        return new View(s, u);
    }

    private SortedMap<String, SourceState> sources() {
        SortedMap<String, SourceState> out = new TreeMap<>();
        Set<String> names = new TreeSet<>(sourceStates.keySet());
        names.addAll(rx.keySet());
        for (String name : names) {
            RxCell cell = rx.get(name);
            out.put(name, new SourceState(sourceStates.getOrDefault(name, ConnectionState.NEW), cell == null ? null : cell.at));
        }
        return out;
    }

    /** 타이머: 생존 줄(밀린 상태 줄 먼저)과 로그. */
    private void tick() {
        try {
            synchronized (this) {
                Connection c = current;
                if (c != null && c.greeted && !c.stopped) {
                    if (c.statusPending) {
                        sendStatusLocked(null);
                    }
                    String hb = EngineFeedProtocol.heartbeatLine(new Heartbeat(clock.instant(), sources(), c.dropped.get()));
                    if (!c.queue.offer(Item.control(hb))) {
                        c.heartbeatsDropped++; // 다시 넣지 않는다(N7): 엔진은 3초 뒤 전달 끊김
                    }
                }
            }
            long now = System.nanoTime();
            long errors = publishErrors.get();
            long dropped = droppedTotal.get();
            if ((errors != errorsLogged || dropped != droppedLogged) && now - lastLogAt >= LOG_INTERVAL_NANOS) {
                LOG.log(Level.WARNING, "엔진 시세 전달: 큐가 차서 버린 이벤트 누계 {0}건, 전달 중 오류 누계 {1}건(녹화·실험실은 그대로)",
                        dropped, errors);
                errorsLogged = errors;
                droppedLogged = dropped;
                lastLogAt = now;
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "엔진 시세 생존 줄 오류: {0}", e.getClass().getName());
        }
    }

    private void checkStall() {
        Connection c;
        synchronized (this) {
            c = current;
        }
        if (c != null && c.writing && System.nanoTime() - c.writingSince > settings.stallLimit().toNanos()) {
            LOG.log(Level.ERROR, "엔진이 {0}초 동안 시세를 읽지 않아 연결을 끊습니다", settings.stallLimit().toSeconds());
            c.stop();
        }
    }

    private void checkSocket() {
        if (closed) {
            return;
        }
        try {
            listening.check();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "엔진 시세 소켓 확인 오류: {0}", e.getClass().getName());
        }
    }

    @Override
    public void close() {
        Connection c;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            c = current;
            route = null;
        }
        listening.close();
        if (c != null) {
            c.stop();
        }
        timer.shutdownNow();
        try {
            acceptor.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 연결 하나 ----

    private final class Connection {
        private final SocketChannel channel;
        private final HandoffQueue<Item> queue = new HandoffQueue<>(settings.queueCapacity());
        private final AtomicLong dropped = new AtomicLong();
        private final RecordingCodec codec = new RecordingCodec();
        /** 이 연결에서 마지막으로 받은 관심 rev(this 잠금). */
        private long rev;
        /** 엔진 인사를 받고 게이트웨이 인사를 넣었다(this 잠금 아래에서 바꾼다). */
        private volatile boolean greeted;
        private boolean statusPending;
        private long heartbeatsDropped;
        private volatile boolean stopped;
        private volatile boolean writing;
        private volatile long writingSince;
        private Thread reader;

        Connection(SocketChannel channel) {
            this.channel = channel;
        }

        /** 게이트웨이 수신 스레드(여러 소스)에서 부른다. 기다리지 않는다. */
        void offer(String sourceId, MarketEvent event, Slot slot) {
            if (slot.since != null) {
                synchronized (slot) {
                    Instant since = slot.since;
                    if (since != null) {
                        Instant end = event.receivedAt().isBefore(since) ? since : event.receivedAt();
                        DataGap gap = new DataGap(event.symbol(), 0, end, end, since, end, DataGap.GapReason.CONSUMER_OVERFLOW);
                        if (!queue.offer(Item.event(SOURCE_ID, gap))) {
                            countDrop();
                            return;
                        }
                        slot.since = null;
                    }
                }
            }
            if (!queue.offer(Item.event(sourceId, event))) {
                countDrop();
                if (slot.since == null) {
                    synchronized (slot) {
                        if (slot.since == null) {
                            slot.since = event.receivedAt();
                        }
                    }
                }
            }
        }

        private void countDrop() {
            dropped.incrementAndGet();
            droppedTotal.incrementAndGet();
        }

        /** 읽기 스레드: 인사 → 쓰기 스레드 시작 → 관심 줄. */
        void read() {
            ScheduledFuture<?> timeout =
                    timer.schedule(this::stop, settings.helloTimeout().toMillis(), TimeUnit.MILLISECONDS);
            Thread writer = null;
            try {
                LineReader in = new LineReader(channel);
                String first = in.next();
                timeout.cancel(false);
                if (first == null || !(EngineFeedProtocol.parseEngineLine(first) instanceof EngineHello hello)) {
                    throw new IOException("엔진 인사가 아닙니다");
                }
                synchronized (EngineFeedServer.this) {
                    if (stopped) {
                        return;
                    }
                    queue.offer(Item.control(EngineFeedProtocol.gatewayHelloLine()));
                    greeted = true;
                    sendStatusLocked(null); // rev 0: 이 연결의 관심 적용 전
                }
                LOG.log(Level.INFO, "엔진 시세 연결: 부팅 {0}", hello.boot());
                writer = daemon("engine-feed-write", Thread.MAX_PRIORITY).newThread(this::write);
                writer.start();
                while (!stopped) {
                    String line = in.next();
                    if (line == null) {
                        break;
                    }
                    if (line == LineReader.TOO_LONG) {
                        onRejected(this, Rejection.LINE_TOO_LONG);
                        continue;
                    }
                    EngineLine parsed;
                    try {
                        parsed = EngineFeedProtocol.parseEngineLine(line);
                    } catch (ProtocolException e) {
                        onRejected(this, e.reason());
                        continue;
                    }
                    if (parsed instanceof Interest i) {
                        onInterest(this, i);
                    } else {
                        onRejected(this, Rejection.UNEXPECTED_LINE);
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (!closed && !stopped) {
                    LOG.log(Level.WARNING, "엔진 시세 연결 끝: {0}", e.getClass().getSimpleName());
                }
            } finally {
                timeout.cancel(false);
                stop();
                ended(this);
            }
        }

        /** 쓰기 스레드. 이벤트 줄은 여기서 인코딩한다. */
        /** 쓰기 스레드. 이벤트 줄은 여기서 인코딩하고, 밀린 줄은 {@link #BATCH_BYTES}까지 모아 한 번에 쓴다. */
        void write() {
            long seq = 0;
            ByteArrayOutputStream batch = new ByteArrayOutputStream(BATCH_BYTES + EngineFeedProtocol.MAX_LINE);
            try {
                while (true) {
                    Item item = queue.take();
                    batch.reset();
                    while (item != null) {
                        if (item == Item.STOP) {
                            return;
                        }
                        String line = item.event == null ? item.text : codec.encode(++seq, item.sourceId, item.event);
                        batch.write(line.getBytes(StandardCharsets.UTF_8));
                        batch.write('\n');
                        item = batch.size() < BATCH_BYTES ? queue.poll() : null;
                    }
                    writeBatch(ByteBuffer.wrap(batch.toByteArray()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                if (!closed && !stopped) {
                    LOG.log(Level.WARNING, "엔진 시세 쓰기 끝: {0}", e.getClass().getSimpleName());
                }
            } finally {
                stop();
            }
        }

        private void writeBatch(ByteBuffer buf) throws IOException {
            writingSince = System.nanoTime();
            writing = true;
            try {
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
            } finally {
                writing = false;
            }
        }

        void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            closeQuietly(channel);
            queue.clear();
            queue.offer(Item.STOP);
        }
    }

    /** 엔진 → 게이트웨이 줄 읽기. 줄 상한을 넘으면 그 줄 끝까지 버리고 {@link #TOO_LONG}을 돌려준다. */
    private static final class LineReader {
        static final String TOO_LONG = new String("<too long>");
        private final SocketChannel ch;
        private final ByteBuffer buf = ByteBuffer.allocate(4096);
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        LineReader(SocketChannel ch) {
            this.ch = ch;
            buf.flip();
        }

        /** 다음 줄. 연결이 끝나면 null. */
        String next() throws IOException {
            out.reset();
            boolean tooLong = false;
            while (true) {
                if (!buf.hasRemaining()) {
                    buf.clear();
                    int n = ch.read(buf);
                    buf.flip();
                    if (n < 0) {
                        return null;
                    }
                    continue;
                }
                byte b = buf.get();
                if (b == '\n') {
                    return tooLong ? TOO_LONG : out.toString(StandardCharsets.UTF_8);
                }
                if (!tooLong) {
                    if (out.size() >= EngineFeedProtocol.MAX_ENGINE_LINE) {
                        tooLong = true;
                        out.reset();
                    } else {
                        out.write(b);
                    }
                }
            }
        }
    }

    /** 종목 하나의 넘침 칸. 관심이 바뀔 때만 만든다. */
    private static final class Slot {
        volatile Instant since;
    }

    private static final class RxCell {
        volatile Instant at;
    }

    private record Route(Connection connection, Map<Symbol, Slot> slots) {}

    private record View(SortedMap<Symbol, String> served, SortedMap<Symbol, Unserved> unserved) {
        static final View EMPTY = new View(Collections.emptySortedMap(), Collections.emptySortedMap());
    }

    private record Item(String sourceId, MarketEvent event, String text) {
        static final Item STOP = new Item("", null, "");

        static Item event(String sourceId, MarketEvent event) {
            return new Item(sourceId, event, null);
        }

        static Item control(String text) {
            return new Item(null, null, text);
        }
    }

    static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name, int priority) {
        return r -> {
            Thread t = Thread.ofPlatform().name(name).daemon(true).unstarted(r);
            t.setPriority(priority);
            return t;
        };
    }

    /** 시험용: 지금 연결에서 넣지 못한 생존 줄 수. */
    synchronized long heartbeatsDroppedForTest() {
        return current == null ? 0 : current.heartbeatsDropped;
    }
}
