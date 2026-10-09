package com.autotrade.marketdata.live;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.LiveMarketDataSource;
import com.autotrade.marketdata.MarketEventListener;
import com.autotrade.marketdata.SourceStateListener;
import com.autotrade.marketdata.Subscription;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 증권사 WebSocket 실시간 시세 소스의 공통 구현(ADR-003 2.1). 메시지 형식은 {@link StreamProtocol}이 맡고,
 * 이 클래스는 접속, 인증 대기, 구독 등록·복구, 무응답 감지, 재연결, 순번, 누락 구간 표시를 맡는다.
 *
 * <p>순서 보장: 메시지 해석과 순번 부여는 잠금 안에서 하고, 수신자 호출은 {@link SerialDispatcher} 하나로 넘긴다.
 * 그래서 {@code sequence}는 수신 순서대로 단조 증가하고, 수신자는 한 스레드에서 그 순서대로 받는다.
 *
 * <p>누락 구간: 연결이 끊기면 마지막으로 메시지를 받은 시각을 기억해 두고, 다시 연결되어 인증이 끝나면
 * 끊길 때 구독하던 종목마다 {@link DataGap}(DISCONNECTED)을 하나씩 낸다. 수신자 큐가 넘쳐 버린 이벤트는
 * 큐가 회복된 뒤 그 종목에 {@link DataGap}(CONSUMER_OVERFLOW)으로 표시한다.
 *
 * <p>상태 알림은 잠금 밖에서 부른다(다른 소스와 서로 잠금을 기다리는 일을 막는다).
 */
public final class StreamingMarketDataSource implements LiveMarketDataSource {

    private static final Logger LOG = System.getLogger(StreamingMarketDataSource.class.getName());

    private final String sourceId;
    private final StreamProtocol protocol;
    private final WsTransport transport;
    private final Clock clock;
    private final Scheduler scheduler;
    private final SerialDispatcher dispatcher;
    private final BackoffPolicy backoff;
    private final Duration idleTimeout;
    private final List<SourceStateListener> stateListeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private final Map<Long, Sub> subs = new LinkedHashMap<>();
    private final SortedSet<Symbol> registered = new TreeSet<>();
    private final Map<Symbol, Instant> overflowSince = new HashMap<>();
    private ConnectionState state = ConnectionState.NEW;
    private WsConnection connection;
    private int generation;
    private boolean awaitingLogin;
    private long nextSubId;
    private long sequence;
    private Instant lastMessageAt;
    private Instant gapStart;
    private SortedSet<Symbol> gapSymbols = new TreeSet<>();
    private Scheduler.Cancellable pendingTask;
    private Scheduler.Cancellable idleTask;
    private final Stats stats = new Stats();

    public StreamingMarketDataSource(
            String sourceId,
            StreamProtocol protocol,
            WsTransport transport,
            Clock clock,
            Scheduler scheduler,
            SerialDispatcher dispatcher,
            BackoffPolicy backoff,
            Duration idleTimeout) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("idleTimeout은 0보다 커야 합니다");
        }
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public int maxSubscriptions() {
        return protocol.maxSymbols();
    }

    @Override
    public ConnectionState state() {
        synchronized (lock) {
            return state;
        }
    }

    @Override
    public void addStateListener(SourceStateListener listener) {
        stateListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** 운영 지표(누락·무시 건수). 스냅샷이다. */
    @Override
    public java.util.Optional<Instant> lastReceivedAt() {
        synchronized (lock) {
            return java.util.Optional.ofNullable(lastMessageAt);
        }
    }

    public Stats.Snapshot stats() {
        synchronized (lock) {
            return stats.snapshot();
        }
    }

    @Override
    public void start() {
        ConnectionState changed;
        synchronized (lock) {
            if (state != ConnectionState.NEW) {
                throw new IllegalStateException("이미 시작했거나 닫은 소스입니다: " + sourceId);
            }
            changed = setState(ConnectionState.CONNECTING);
            pendingTask = scheduler.schedule(Duration.ZERO, this::attempt);
            scheduleIdleCheck();
        }
        fire(changed);
    }

    @Override
    public Subscription subscribe(Set<Symbol> symbols, MarketEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        SortedSet<Symbol> wanted = new TreeSet<>(symbols);
        for (Symbol s : wanted) {
            if (s.market() != protocol.market()) {
                throw new IllegalArgumentException(sourceId + "는 " + protocol.market() + " 시세만 받습니다: " + s);
            }
        }
        synchronized (lock) {
            if (state == ConnectionState.CLOSED) {
                throw new IllegalStateException("닫힌 소스입니다: " + sourceId);
            }
            SortedSet<Symbol> union = new TreeSet<>(registered);
            union.addAll(wanted);
            if (union.size() > protocol.maxSymbols()) {
                throw new IllegalArgumentException(sourceId + " 구독 한도 " + protocol.maxSymbols() + "종목을 넘습니다: " + union.size());
            }
            SortedSet<Symbol> added = new TreeSet<>(wanted);
            added.removeAll(registered);
            long id = nextSubId++;
            Sub sub = new Sub(id, Collections.unmodifiableSortedSet(wanted), listener);
            subs.put(id, sub);
            registered.addAll(added);
            if (isReady()) {
                sendAll(protocol.subscribeMessages(added));
            }
            return sub;
        }
    }

    @Override
    public void close() {
        ConnectionState changed;
        synchronized (lock) {
            if (state == ConnectionState.CLOSED) {
                return;
            }
            generation++;
            cancelTasks();
            if (connection != null) {
                connection.close();
                connection = null;
            }
            changed = setState(ConnectionState.CLOSED);
        }
        dispatcher.close();
        fire(changed);
    }

    // ---- 연결 수명 ----

    private void attempt() {
        int gen;
        List<String> opening;
        synchronized (lock) {
            if (state == ConnectionState.CLOSED) {
                return;
            }
            gen = generation;
        }
        try {
            opening = protocol.openingMessages();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            // 예외 메시지에는 비밀값이 섞일 수 있어 종류만 남긴다
            LOG.log(Level.WARNING, "{0} 인증 준비 실패({1}), 재연결 대기", sourceId, e.getClass().getSimpleName());
            onConnectFailed(gen);
            return;
        }
        transport.connect(protocol.endpoint(), new Handler(gen)).whenComplete((conn, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "{0} 접속 실패({1}), 재연결 대기", sourceId, err.getClass().getSimpleName());
                onConnectFailed(gen);
            } else {
                onOpened(gen, conn, opening);
            }
        });
    }

    private void onConnectFailed(int gen) {
        ConnectionState changed;
        synchronized (lock) {
            if (gen != generation || state == ConnectionState.CLOSED) {
                return;
            }
            stats.connectFailures++;
            // 접속 실패도 끊김으로 알린다. 기동 직후부터 못 붙는 소스를 게이트웨이가 배분에서 빼야 한다(ADR-003 2.2)
            changed = setState(ConnectionState.DISCONNECTED);
            scheduleReconnect();
        }
        fire(changed);
    }

    private void onOpened(int gen, WsConnection conn, List<String> opening) {
        ConnectionState changed = null;
        synchronized (lock) {
            if (gen != generation || state == ConnectionState.CLOSED) {
                conn.close();
                return;
            }
            connection = conn;
            lastMessageAt = clock.instant();
            sendAll(opening);
            awaitingLogin = protocol.requiresLoginAck();
            if (!awaitingLogin) {
                changed = becomeReady();
            }
        }
        fire(changed);
    }

    /** 인증까지 끝났다: 누락 구간을 알리고 구독을 복구한다. 잠금 안에서 부른다. */
    private ConnectionState becomeReady() {
        awaitingLogin = false;
        backoff.reset();
        Instant now = clock.instant();
        if (gapStart != null) {
            for (Symbol s : gapSymbols) {
                if (!emit(new RawGap(s, gapStart, now, DataGap.GapReason.DISCONNECTED), now)) {
                    // 큐가 차서 공백 표시를 못 넘겼다. 다음 체결 앞에 넘침 공백으로 끊긴 시각부터 표시한다
                    overflowSince.putIfAbsent(s, gapStart);
                    stats.overflowDropped++;
                }
            }
            stats.gaps++;
            gapStart = null;
            gapSymbols = new TreeSet<>();
        }
        sendAll(protocol.subscribeMessages(new TreeSet<>(registered)));
        return setState(ConnectionState.CONNECTED);
    }

    private void dropConnection(int gen, String why) {
        ConnectionState changed;
        synchronized (lock) {
            if (gen != generation || state == ConnectionState.CLOSED) {
                return;
            }
            generation++;
            if (connection != null) {
                connection.close();
                connection = null;
            }
            if (gapStart == null && state == ConnectionState.CONNECTED) {
                gapStart = lastMessageAt != null ? lastMessageAt : clock.instant();
                gapSymbols = new TreeSet<>(registered);
            }
            stats.disconnects++;
            LOG.log(Level.WARNING, "{0} 연결 끊김({1}), 재연결 대기", sourceId, why);
            changed = setState(ConnectionState.DISCONNECTED);
            scheduleReconnect();
        }
        fire(changed);
    }

    private void scheduleReconnect() {
        if (state == ConnectionState.CLOSED) {
            return;
        }
        if (pendingTask != null) {
            pendingTask.cancel();
        }
        pendingTask = scheduler.schedule(backoff.nextDelay(), () -> {
            ConnectionState changed;
            synchronized (lock) {
                if (state == ConnectionState.CLOSED) {
                    return;
                }
                changed = setState(ConnectionState.CONNECTING);
            }
            fire(changed);
            attempt();
        });
    }

    private void scheduleIdleCheck() {
        Duration period = idleTimeout.dividedBy(2);
        idleTask = scheduler.schedule(period.isZero() ? idleTimeout : period, () -> {
            int gen = -1;
            synchronized (lock) {
                if (state == ConnectionState.CLOSED) {
                    return;
                }
                if (state == ConnectionState.CONNECTED || awaitingLogin) {
                    Instant now = clock.instant();
                    if (lastMessageAt != null && Duration.between(lastMessageAt, now).compareTo(idleTimeout) > 0) {
                        gen = generation;
                    }
                }
                scheduleIdleCheck();
            }
            if (gen >= 0) {
                dropConnection(gen, "무응답 " + idleTimeout.toSeconds() + "초");
            }
        });
    }

    private void cancelTasks() {
        if (pendingTask != null) {
            pendingTask.cancel();
        }
        if (idleTask != null) {
            idleTask.cancel();
        }
    }

    // ---- 수신 ----

    private void onText(int gen, String text) {
        ConnectionState changed = null;
        int dropGen = -1;
        String dropWhy = null;
        synchronized (lock) {
            if (gen != generation || state == ConnectionState.CLOSED) {
                return;
            }
            Instant now = clock.instant();
            lastMessageAt = now;
            Decoded d;
            try {
                d = protocol.decode(text, now);
            } catch (RuntimeException e) {
                // 해석 못 한 메시지 하나 때문에 연결을 잃지 않는다. 본문은 로그에 남기지 않는다(ADR-003 4장)
                stats.malformed++;
                LOG.log(Level.WARNING, "{0} 해석 실패 메시지 버림({1})", sourceId, e.getClass().getSimpleName());
                return;
            }
            sendAll(d.replies());
            switch (d.control()) {
                case LOGIN_OK -> {
                    if (awaitingLogin) {
                        changed = becomeReady();
                    }
                }
                case LOGIN_FAILED -> {
                    protocol.invalidateCredentials();
                    dropGen = gen;
                    dropWhy = "인증 실패 " + d.detail();
                }
                case SUBSCRIBE_REJECTED -> {
                    stats.subscribeRejected++;
                    LOG.log(Level.WARNING, "{0} 구독 거절: {1}", sourceId, d.detail());
                }
                case IGNORED -> stats.ignored++;
                case NONE -> { }
            }
            for (RawQuote q : d.quotes()) {
                if (!registered.contains(q.symbol())) {
                    stats.unsubscribed++;
                    continue;
                }
                emitQuote(q, now);
            }
        }
        if (dropGen >= 0) {
            dropConnection(dropGen, dropWhy);
        }
        fire(changed);
    }

    private void emitQuote(RawQuote q, Instant receivedAt) {
        Instant since = overflowSince.get(q.symbol());
        if (since != null) {
            if (!emit(new RawGap(q.symbol(), since, receivedAt, DataGap.GapReason.CONSUMER_OVERFLOW), receivedAt)) {
                stats.overflowDropped++;
                return;
            }
            overflowSince.remove(q.symbol());
        }
        if (!emit(q, receivedAt)) {
            overflowSince.put(q.symbol(), receivedAt);
            stats.overflowDropped++;
        }
    }

    /** 순번을 매겨 수신자에게 넘긴다. 큐가 가득 차면 순번을 쓰지 않고 {@code false}. 잠금 안에서 부른다. */
    private boolean emit(Object raw, Instant receivedAt) {
        Symbol symbol = raw instanceof RawQuote q ? q.symbol() : ((RawGap) raw).symbol();
        List<MarketEventListener> targets = new ArrayList<>();
        for (Sub sub : subs.values()) {
            if (sub.symbols.contains(symbol)) {
                targets.add(sub.listener);
            }
        }
        if (targets.isEmpty()) {
            return true;
        }
        long seq = sequence + 1;
        MarketEvent event = raw instanceof RawQuote q
                ? q.toEvent(seq, receivedAt)
                : ((RawGap) raw).toEvent(seq, receivedAt);
        boolean accepted = dispatcher.offer(() -> {
            for (MarketEventListener l : targets) {
                l.onEvent(event);
            }
        });
        if (accepted) {
            sequence = seq;
        }
        return accepted;
    }

    private boolean isReady() {
        return state == ConnectionState.CONNECTED && connection != null && !awaitingLogin;
    }

    private void sendAll(List<String> messages) {
        if (connection == null) {
            return;
        }
        for (String m : messages) {
            connection.send(m);
        }
    }

    private ConnectionState setState(ConnectionState next) {
        if (state == next) {
            return null;
        }
        state = next;
        return next;
    }

    private void fire(ConnectionState changed) {
        if (changed == null) {
            return;
        }
        for (SourceStateListener l : stateListeners) {
            try {
                l.onStateChanged(sourceId, changed);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "{0} 상태 수신자 오류: {1}", sourceId, e.getClass().getName());
            }
        }
    }

    private void unsubscribe(Sub sub) {
        synchronized (lock) {
            if (subs.remove(sub.id) == null) {
                return;
            }
            SortedSet<Symbol> stillWanted = new TreeSet<>();
            for (Sub other : subs.values()) {
                stillWanted.addAll(other.symbols);
            }
            SortedSet<Symbol> removed = new TreeSet<>(sub.symbols);
            removed.removeAll(stillWanted);
            registered.removeAll(removed);
            for (Symbol s : removed) {
                overflowSince.remove(s);
            }
            if (isReady()) {
                sendAll(protocol.unsubscribeMessages(removed));
            }
        }
    }

    private record RawGap(Symbol symbol, Instant start, Instant end, DataGap.GapReason reason) {
        MarketEvent toEvent(long sequence, Instant receivedAt) {
            return new DataGap(symbol, sequence, receivedAt, receivedAt, start, end, reason);
        }
    }

    private final class Sub implements Subscription {
        private final long id;
        private final SortedSet<Symbol> symbols;
        private final MarketEventListener listener;

        Sub(long id, SortedSet<Symbol> symbols, MarketEventListener listener) {
            this.id = id;
            this.symbols = symbols;
            this.listener = listener;
        }

        @Override
        public SortedSet<Symbol> symbols() {
            return symbols;
        }

        @Override
        public void close() {
            unsubscribe(this);
        }
    }

    private final class Handler implements WsHandler {
        private final int gen;

        Handler(int gen) {
            this.gen = gen;
        }

        @Override
        public void onText(String text) {
            StreamingMarketDataSource.this.onText(gen, text);
        }

        @Override
        public void onClosed(int statusCode, String reason) {
            dropConnection(gen, "서버 종료 " + statusCode);
        }

        @Override
        public void onError(Throwable error) {
            dropConnection(gen, "오류 " + error.getClass().getSimpleName());
        }
    }

    /** 운영 지표. */
    public static final class Stats {
        long connectFailures;
        long disconnects;
        long gaps;
        long subscribeRejected;
        long ignored;
        long unsubscribed;
        long overflowDropped;
        long malformed;

        Snapshot snapshot() {
            return new Snapshot(connectFailures, disconnects, gaps, subscribeRejected, ignored, unsubscribed, overflowDropped,
                    malformed);
        }

        public record Snapshot(
                long connectFailures,
                long disconnects,
                long gaps,
                long subscribeRejected,
                long ignored,
                long unsubscribed,
                long overflowDropped,
                long malformed) {}
    }
}
