package com.autotrade.marketdata.feed;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.recording.EventSequences;
import com.autotrade.marketdata.recording.RecordingCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실험실 쪽 시세 전달 받는 쪽(ADR-010 2.2, D-076). 게이트웨이의 {@link FeedServer}에 연결해 이벤트를 받는다.
 *
 * <ul>
 *   <li>연결이 끊기거나 게이트웨이가 없으면 1초부터 두 배씩 최대 30초 간격으로 다시 붙는다. 게이트웨이를 기다리게 하지 않는다.</li>
 *   <li>다시 붙으면 끊기기 전에 받은 종목마다 {@link DataGap}(DISCONNECTED, 출처 {@value #SOURCE_ID})을 먼저 넘긴다. 구간은
 *       끊기기 전 마지막으로 받은 이벤트의 수신 시각부터 다시 붙은 시각(이쪽 시계)까지다.</li>
 *   <li>받은 이벤트의 순번은 게이트웨이 소스 순번({@code srcSeq})으로 되돌려 넘긴다(녹화와 같은 줄이 되게).</li>
 *   <li>처리기는 이 클래스의 수신 스레드 하나에서 순서대로 불린다. 처리기는 빨리 돌아와야 한다(느린 처리는 자기 큐로).</li>
 * </ul>
 * 수신 스레드는 데몬이다.
 */
public final class FeedClient implements AutoCloseable {

    /** 받는 쪽이 만든 공백 줄의 출처 이름. */
    public static final String SOURCE_ID = "FEED-CLIENT";
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private static final Logger LOG = System.getLogger(FeedClient.class.getName());

    /** 받은 이벤트 처리기. */
    @FunctionalInterface
    public interface Handler {
        /** @param sourceId 원래 소스 이름(녹화의 {@code src}) */
        void onEvent(String sourceId, MarketEvent event);
    }

    private final Path socket;
    private final FeedProtocol.Subscribe subscribe;
    private final Handler handler;
    private final Clock clock;
    private final Duration firstBackoff;
    private final Duration maxBackoff;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final SortedSet<Symbol> seen = new TreeSet<>();
    private Instant lastReceivedAt;
    private volatile ConnectionState state = ConnectionState.NEW;
    private volatile String lastError = "";
    private volatile SocketChannel channel;
    private volatile boolean closed;
    private Thread thread;

    /**
     * @param client 받는 쪽 이름(게이트웨이 로그에 남는다, 영문 소문자·숫자·- 32자 이내)
     */
    public FeedClient(Path socket, Set<Market> markets, String client, Handler handler, Clock clock) {
        this(socket, markets, client, handler, clock, FIRST_BACKOFF, MAX_BACKOFF);
    }

    FeedClient(Path socket, Set<Market> markets, String client, Handler handler, Clock clock, Duration firstBackoff,
            Duration maxBackoff) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.subscribe = new FeedProtocol.Subscribe(markets, client);
        this.handler = Objects.requireNonNull(handler, "handler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.firstBackoff = firstBackoff;
        this.maxBackoff = maxBackoff;
    }

    /** 수신 스레드를 시작한다. 한 번만 부른다. */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("이미 시작했습니다");
        }
        thread = Thread.ofPlatform().name("feed-client").daemon(true).start(this::loop);
    }

    public ConnectionState state() {
        return state;
    }

    /** 받은 이벤트 수(공백 줄 포함). */
    public long receivedCount() {
        return received.get();
    }

    /** 다시 붙은 횟수. */
    public long reconnectCount() {
        return reconnects.get();
    }

    /** 마지막 연결 실패 이유(없으면 빈 문자열). 화면 표시용. */
    public String lastError() {
        return lastError;
    }

    private void loop() {
        Duration backoff = firstBackoff;
        boolean everConnected = false;
        while (!closed) {
            state = everConnected ? ConnectionState.DISCONNECTED : ConnectionState.CONNECTING;
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel = ch;
                if (closed) {
                    return;
                }
                ch.connect(UnixDomainSocketAddress.of(socket));
                OutputStream out = Channels.newOutputStream(ch);
                out.write((FeedProtocol.subscribeLine(subscribe) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch),
                        StandardCharsets.UTF_8), 64 * 1024);
                String hello = in.readLine();
                if (hello == null) {
                    throw new IOException("게이트웨이가 인사 없이 끊었습니다");
                }
                String bad = FeedProtocol.checkHello(hello);
                if (!bad.isEmpty()) {
                    throw new IOException(bad);
                }
                state = ConnectionState.CONNECTED;
                lastError = "";
                backoff = firstBackoff;
                if (everConnected) {
                    reconnects.incrementAndGet();
                }
                everConnected = true;
                emitReconnectGaps();
                LOG.log(Level.INFO, "시세 전달 연결: {0}", subscribe.markets());
                RecordingCodec codec = new RecordingCodec();
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    if (line.length() > FeedProtocol.MAX_LINE) {
                        throw new IOException("줄이 너무 깁니다");
                    }
                    RecordingCodec.Line l = codec.decode(line);
                    if (!subscribe.markets().contains(l.event().symbol().market())) {
                        throw new IOException("구독하지 않은 시장 이벤트를 받았습니다");
                    }
                    deliver(l.sourceId(), EventSequences.withSequence(l.event(), l.sourceSequence()));
                }
            } catch (IOException | RuntimeException e) {
                if (!closed) {
                    lastError = e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "");
                }
            } finally {
                channel = null;
            }
            if (closed) {
                break;
            }
            state = everConnected ? ConnectionState.DISCONNECTED : ConnectionState.CONNECTING;
            try {
                Thread.sleep(backoff.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = backoff.multipliedBy(2).compareTo(maxBackoff) > 0 ? maxBackoff : backoff.multipliedBy(2);
        }
        state = ConnectionState.CLOSED;
    }

    private void deliver(String sourceId, MarketEvent event) {
        seen.add(event.symbol());
        lastReceivedAt = event.receivedAt();
        received.incrementAndGet();
        handler.onEvent(sourceId, event);
    }

    /** 끊기기 전에 받은 종목마다 끊긴 구간 공백을 넘긴다(종목 이름순). */
    private void emitReconnectGaps() {
        if (seen.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Instant start = lastReceivedAt == null || lastReceivedAt.isAfter(now) ? now : lastReceivedAt;
        for (Symbol s : java.util.List.copyOf(seen)) {
            handler.onEvent(SOURCE_ID, new DataGap(s, 0, now, now, start, now, DataGap.GapReason.DISCONNECTED));
            received.incrementAndGet();
        }
    }

    @Override
    public void close() {
        closed = true;
        SocketChannel ch = channel;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state = ConnectionState.CLOSED;
    }
}
