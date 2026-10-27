package com.autotrade.marketdata.enginefeed;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.GatewayControl;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.GatewayHello;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.HeartbeatLine;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Interest;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.StatusLine;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 실전 엔진 쪽 시세 받는 쪽(ADR-016 2.3·2.7). 게이트웨이의 엔진 전달 서버에 붙어 인사와 관심 종목을 보내고, 이벤트·상태·생존
 * 줄을 받아 {@link Listener}에 넘긴다.
 *
 * <ul>
 *   <li>연결이 안 되거나 끊기면 1초 간격으로 다시 붙는다. 로그는 1·2·4·8…번째 실패에만 남긴다(ADR-016 2.7).</li>
 *   <li>게이트웨이 인사가 {@link #HELLO_TIMEOUT} 안에 오지 않거나 형식·녹화 버전이 다르면 끊는다. 이유는
 *       {@link Listener#onDisconnected}로 넘긴다(긴급 알림은 엔진이 정한다, G4).</li>
 *   <li>관심 종목은 연결마다 {@code rev} 1부터 다시 센다. 다시 붙으면 마지막으로 정한 관심을 {@code rev} 1로 다시 보낸다.</li>
 *   <li>받는 쪽은 받은 이벤트를 따로 저장하지 않는다. 판단·보호 폴링 전환은 엔진(G4)이 한다.</li>
 * </ul>
 *
 * 처리기는 이 클래스의 수신 스레드 하나에서 순서대로 불린다. 처리기는 빨리 돌아와야 한다(ADR-016 2.6: 최근 가격 표만 고치고
 * 계좌 큐에 넣기). 스레드는 모두 데몬이다.
 */
public final class EngineFeedClient implements AutoCloseable {

    public static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);
    public static final Duration HELLO_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger LOG = System.getLogger(EngineFeedClient.class.getName());

    /** 받은 줄 처리기. 모두 수신 스레드에서 불린다. */
    public interface Listener {
        /** 게이트웨이 인사를 확인했다. 이 뒤의 상태 줄부터 이 연결의 {@code rev}로 읽는다. */
        void onConnected();

        /** 이벤트 줄(녹화 형식). {@code line.seq()}는 연결마다 1부터 빈틈 없는 전달 순번이다. */
        void onEvent(RecordingCodec.Line line);

        void onStatus(EngineFeedProtocol.Status status);

        void onHeartbeat(EngineFeedProtocol.Heartbeat heartbeat);

        /** 연결이 끝났거나 붙지 못했다. 처음 붙기 전 실패에도 불린다. */
        void onDisconnected(String reason);
    }

    private final Path socket;
    private final String boot;
    private final Listener listener;
    private final Duration retryInterval;
    private final Duration helloTimeout;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("engine-feed-client-timer").daemon(true).factory());
    private final Object writeLock = new Object();
    /** 마지막으로 정한 관심(아직 없으면 null). {@link #writeLock} 아래에서만 쓴다. */
    private Set<Symbol> wantProtect;
    private Set<Symbol> wantEntry;
    /** 이 연결에서 마지막으로 보낸 {@code rev}. {@link #writeLock} 아래에서만 쓴다. */
    private long rev;
    /** 인사를 보낸 연결. {@link #writeLock} 아래에서만 바꾼다. */
    private SocketChannel writable;
    private volatile ConnectionState state = ConnectionState.NEW;
    private volatile SocketChannel channel;
    private volatile boolean closed;
    private Thread thread;

    /**
     * @param boot 엔진 부팅 표지({@code [A-Za-z0-9-]{1,40}}, 게이트웨이 로그 대조용)
     */
    public EngineFeedClient(Path socket, String boot, Listener listener) {
        this(socket, boot, listener, RETRY_INTERVAL, HELLO_TIMEOUT);
    }

    EngineFeedClient(Path socket, String boot, Listener listener, Duration retryInterval, Duration helloTimeout) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.boot = new EngineFeedProtocol.EngineHello(boot).boot();
        this.listener = Objects.requireNonNull(listener, "listener");
        this.retryInterval = retryInterval;
        this.helloTimeout = helloTimeout;
    }

    /** 수신 스레드를 시작한다. 한 번만 부른다. */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("이미 시작했습니다");
        }
        thread = Thread.ofPlatform().name("engine-feed-client").daemon(true).start(this::loop);
    }

    public ConnectionState state() {
        return state;
    }

    /**
     * 관심 종목을 정한다(전체 교체). 연결돼 있으면 바로 보낸다.
     *
     * @return 이 연결에서 보낸 {@code rev}. 연결이 없어 보내지 못했으면 0(다시 붙으면 {@code rev} 1로 보낸다)
     * @throws EngineFeedProtocol.ProtocolException 상한을 넘으면(보내지 않고 앞 관심을 그대로 둔다)
     */
    public long interest(Set<Symbol> protect, Set<Symbol> entry) {
        // 상한 검사를 먼저 해서 넘으면 앞 관심을 그대로 둔다
        new Interest(1, new TreeSet<>(protect), new TreeSet<>(entry));
        synchronized (writeLock) {
            wantProtect = Set.copyOf(protect);
            wantEntry = Set.copyOf(entry);
            if (writable == null) {
                return 0;
            }
            try {
                return sendInterestLocked();
            } catch (IOException e) {
                closeQuietly(writable);
                return 0;
            }
        }
    }

    private long sendInterestLocked() throws IOException {
        Interest i = new Interest(++rev, new TreeSet<>(wantProtect), new TreeSet<>(wantEntry));
        write(writable, EngineFeedProtocol.interestLine(i));
        return i.rev();
    }

    private void loop() {
        long failures = 0;
        while (!closed) {
            state = ConnectionState.CONNECTING;
            String reason;
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel = ch;
                if (closed) {
                    break;
                }
                ch.connect(UnixDomainSocketAddress.of(socket));
                synchronized (writeLock) {
                    writable = ch;
                    rev = 0;
                    write(ch, EngineFeedProtocol.engineHelloLine(boot));
                    if (wantProtect != null) {
                        sendInterestLocked();
                    }
                }
                reason = receive(ch);
                failures = 0;
            } catch (IOException | RuntimeException e) {
                reason = (state == ConnectionState.CONNECTED ? "" : "게이트웨이 인사 전에 끝남: ")
                        + e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "");
                failures++;
                if ((failures & (failures - 1)) == 0) {
                    LOG.log(Level.WARNING, "엔진 시세 연결 실패({0}번째): {1}", failures, reason);
                }
            } finally {
                synchronized (writeLock) {
                    writable = null;
                }
                channel = null;
            }
            if (closed) {
                break;
            }
            state = ConnectionState.DISCONNECTED;
            listener.onDisconnected(reason);
            try {
                Thread.sleep(retryInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        state = ConnectionState.CLOSED;
    }

    /** 인사를 확인하고 줄을 받는다. 연결이 정상으로 끝나면 이유를 돌려주고, 형식 오류는 예외로 끝난다. */
    private String receive(SocketChannel ch) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8),
                64 * 1024);
        ScheduledFuture<?> timeout = timer.schedule(() -> closeQuietly(ch), helloTimeout.toMillis(), TimeUnit.MILLISECONDS);
        String helloText;
        try {
            helloText = in.readLine();
        } finally {
            timeout.cancel(false);
        }
        if (helloText == null) {
            throw new IOException("게이트웨이가 인사 없이 끊었습니다(다른 엔진이 붙어 있거나 인사 시간 초과)");
        }
        GatewayControl hello = EngineFeedProtocol.parseGatewayControl(EngineFeedProtocol.readTree(helloText));
        if (!(hello instanceof GatewayHello h)) {
            throw new IOException("게이트웨이 인사가 아닙니다");
        }
        String bad = EngineFeedProtocol.checkGatewayHello(h);
        if (!bad.isEmpty()) {
            throw new IOException(bad);
        }
        state = ConnectionState.CONNECTED;
        LOG.log(Level.INFO, "엔진 시세 연결: {0}", socket.getFileName());
        listener.onConnected();
        RecordingCodec codec = new RecordingCodec();
        String line;
        while (!closed && (line = in.readLine()) != null) {
            if (line.length() > EngineFeedProtocol.MAX_LINE) {
                throw new IOException("줄이 너무 깁니다");
            }
            JsonNode n = EngineFeedProtocol.readTree(line);
            if (EngineFeedProtocol.isEventLine(n)) {
                listener.onEvent(codec.decode(line));
                continue;
            }
            switch (EngineFeedProtocol.parseGatewayControl(n)) {
                case StatusLine s -> listener.onStatus(s.status());
                case HeartbeatLine hb -> listener.onHeartbeat(hb.heartbeat());
                case GatewayHello again -> throw new IOException("게이트웨이 인사가 다시 왔습니다");
            }
        }
        return "게이트웨이가 연결을 닫았습니다";
    }

    private static void write(SocketChannel ch, String text) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap((text + "\n").getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }

    @Override
    public void close() {
        closed = true;
        SocketChannel ch = channel;
        if (ch != null) {
            closeQuietly(ch);
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
        timer.shutdownNow();
        state = ConnectionState.CLOSED;
    }
}
