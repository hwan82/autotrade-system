package com.autotrade.marketdata.feed;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.recording.RecordingCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 시세 게이트웨이 쪽 실시간 전달 서버(ADR-010 2.1~2.2, D-076). 게이트웨이가 받은 이벤트를 연결된 받는 쪽(실험실)에 그대로 넘긴다.
 *
 * <p><b>게이트웨이를 막지 않는다</b>(절대 규칙 6, broker-and-fault-design 5장). {@link #publish}는 받는 쪽마다 크기 제한 큐에
 * 넣기만 하고 기다리지 않으며 예외를 던지지 않는다. 소켓 쓰기는 받는 쪽마다 전용 스레드가 한다. 큐가 차면 그 받는 쪽에만 이벤트를
 * 버리고, 그 종목의 다음 이벤트 앞에 {@link DataGap}(CONSUMER_OVERFLOW, 출처 {@value #SOURCE_ID})을 넣는다. 받는 쪽 하나가
 * 느려도 다른 받는 쪽과 녹화는 영향을 받지 않는다. 쓰기가 {@link #STALL_LIMIT} 동안 한 줄도 못 나가면 그 연결을 끊는다.
 *
 * <p>받는 쪽은 {@link #MAX_SUBSCRIBERS}개까지다. 구독 줄을 {@link #SUBSCRIBE_TIMEOUT} 안에 보내지 않으면 끊는다. 받는 쪽이
 * 보내는 것은 구독 줄 하나뿐이고 주문·설정 같은 명령은 받지 않는다(ADR-010 2.2).
 *
 * <p>모든 스레드는 데몬이다. 전달 서버가 죽어도 게이트웨이의 수신·녹화는 계속된다.
 */
public final class FeedServer implements AutoCloseable {

    public static final int MAX_SUBSCRIBERS = 4;
    /** 받는 쪽마다 큐 크기. 키움 200종목 × 초당 수십 건을 몇 초 버틴다(녹화기 10만 건의 절반) `[추론]`. */
    public static final int QUEUE_CAPACITY = 50_000;
    public static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration STALL_LIMIT = Duration.ofSeconds(30);
    /** 전달 서버가 직접 만든 공백 줄의 출처 이름. */
    public static final String SOURCE_ID = "FEED";

    private static final Logger LOG = System.getLogger(FeedServer.class.getName());
    private static final Item STOP = new Item("", null);

    private final Path socket;
    private final int queueCapacity;
    private final long stallNanos;
    private final ServerSocketChannel server;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("feed-timer"));
    private final Thread acceptor;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    private FeedServer(Path socket, int queueCapacity, Duration stallLimit) throws IOException {
        this.socket = socket;
        this.queueCapacity = queueCapacity;
        this.stallNanos = stallLimit.toNanos();
        FeedSocket.prepareDirectory(socket.toAbsolutePath().getParent());
        FeedSocket.removeStaleSocket(socket);
        ServerSocketChannel opened = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            opened.bind(UnixDomainSocketAddress.of(socket));
            FeedSocket.restrictSocketFile(socket);
        } catch (IOException e) {
            opened.close();
            throw new IOException("시세 전달 소켓을 열지 못했습니다(" + e.getMessage()
                    + "). 소켓 경로가 길면(약 100자 초과) AUTOTRADE_CONFIG_DIR을 짧은 경로로 바꾸세요", e);
        }
        this.server = opened;
        this.acceptor = Thread.ofPlatform().name("feed-accept").daemon(true).start(this::acceptLoop);
        long checkMillis = Math.max(100, stallLimit.toMillis() / 3);
        timer.scheduleWithFixedDelay(this::checkStalls, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 소켓을 열고 받는 쪽을 기다린다.
     *
     * @throws IOException 소켓을 열 수 없거나 다른 게이트웨이가 이미 듣고 있을 때
     */
    public static FeedServer open(Path socket) throws IOException {
        return new FeedServer(socket, QUEUE_CAPACITY, STALL_LIMIT);
    }

    /** 큐 크기와 멈춤 판단 시간을 바꾸는 입구(테스트). */
    static FeedServer open(Path socket, int queueCapacity, Duration stallLimit) throws IOException {
        return new FeedServer(socket, queueCapacity, stallLimit);
    }

    /**
     * 이벤트 하나를 구독한 받는 쪽마다 큐에 넣는다. 기다리지 않고 예외를 던지지 않는다(게이트웨이 수신 스레드에서 부른다).
     *
     * @param sourceId 원래 소스 이름(녹화의 {@code src}와 같다)
     */
    public void publish(String sourceId, MarketEvent event) {
        if (closed) {
            return;
        }
        try {
            for (Subscriber s : subscribers) {
                s.offer(sourceId, event);
            }
        } catch (RuntimeException e) {
            // 전달 실패가 게이트웨이 수신·녹화로 번지지 않게 한다
            LOG.log(Level.WARNING, "시세 전달 중 오류(게이트웨이는 계속): {0}", e.getClass().getName());
        }
    }

    /** 지금 연결된 받는 쪽 수(구독 줄을 받은 것만). */
    public int subscriberCount() {
        return (int) subscribers.stream().filter(s -> s.subscribed).count();
    }

    /** 큐가 차서 버린 이벤트 수(모든 받는 쪽 합). */
    public long droppedCount() {
        return dropped.get();
    }

    public Path socket() {
        return socket;
    }

    private void acceptLoop() {
        while (!closed) {
            SocketChannel ch;
            try {
                ch = server.accept();
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (!closed) {
                    LOG.log(Level.WARNING, "시세 전달 연결 받기 실패: {0}", e.getClass().getName());
                }
                continue;
            }
            if (subscribers.size() >= MAX_SUBSCRIBERS) {
                LOG.log(Level.WARNING, "시세 전달 받는 쪽이 상한({0})이라 새 연결을 닫습니다", MAX_SUBSCRIBERS);
                closeQuietly(ch);
                continue;
            }
            Subscriber s = new Subscriber(ch);
            subscribers.add(s);
            s.thread = Thread.ofPlatform().name("feed-conn").daemon(true).start(s::run);
        }
    }

    private void checkStalls() {
        long now = System.nanoTime();
        for (Subscriber s : subscribers) {
            if (s.subscribed && !s.queue.isEmpty() && now - s.lastProgress > stallNanos) {
                LOG.log(Level.WARNING, "시세 전달 받는 쪽({0})이 {1}초 동안 읽지 않아 연결을 끊습니다", s.client,
                        stallNanos / 1_000_000_000L);
                s.stop();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
        subscribers.forEach(Subscriber::stop);
        timer.shutdownNow();
        try {
            acceptor.join(1000);
            Files.deleteIfExists(socket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "시세 전달 소켓 파일을 지우지 못했습니다: {0}", e.getClass().getName());
        }
    }

    /** 받는 쪽 하나. 큐에 넣는 쪽은 게이트웨이 수신 스레드, 꺼내 쓰는 쪽은 이 받는 쪽 전용 스레드. */
    private final class Subscriber {
        private final SocketChannel channel;
        private final BlockingQueue<Item> queue = new ArrayBlockingQueue<>(queueCapacity);
        /** 버리기 시작한 시각(종목별). 다음 이벤트 앞에 공백을 넣고 지운다. publish 잠금 아래에서만 쓴다. */
        private final Map<Symbol, Instant> overflowSince = new HashMap<>();
        private final RecordingCodec codec = new RecordingCodec();
        private volatile java.util.Set<com.autotrade.domain.market.Market> markets = java.util.Set.of();
        private volatile boolean subscribed;
        private volatile long lastProgress = System.nanoTime();
        private volatile String client = "?";
        private Thread thread;

        Subscriber(SocketChannel channel) {
            this.channel = channel;
        }

        synchronized void offer(String sourceId, MarketEvent event) {
            if (!subscribed || !markets.contains(event.symbol().market())) {
                return;
            }
            Instant since = overflowSince.get(event.symbol());
            if (since != null) {
                DataGap gap = new DataGap(event.symbol(), 0, event.receivedAt(), event.receivedAt(), since,
                        event.receivedAt(), DataGap.GapReason.CONSUMER_OVERFLOW);
                if (!queue.offer(new Item(SOURCE_ID, gap))) {
                    dropped.incrementAndGet();
                    return;
                }
                overflowSince.remove(event.symbol());
            }
            if (!queue.offer(new Item(sourceId, event))) {
                dropped.incrementAndGet();
                overflowSince.putIfAbsent(event.symbol(), event.receivedAt());
            }
        }

        void run() {
            ScheduledFuture<?> timeout = timer.schedule(this::stop, SUBSCRIBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            try {
                String line = readLine(channel, FeedProtocol.MAX_SUBSCRIBE_LINE);
                timeout.cancel(false);
                FeedProtocol.Subscribe sub = FeedProtocol.parseSubscribe(line);
                client = sub.client();
                markets = sub.markets();
                writeLine(FeedProtocol.helloLine());
                lastProgress = System.nanoTime();
                subscribed = true;
                LOG.log(Level.INFO, "시세 전달 받는 쪽 연결: {0} {1}", client, markets);
                Thread.ofPlatform().name("feed-conn-watch").daemon(true).start(this::watchClose);
                long seq = 0;
                while (true) {
                    Item item = queue.take();
                    if (item == STOP) {
                        break;
                    }
                    writeLine(codec.encode(++seq, item.sourceId, item.event));
                    lastProgress = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                if (!closed) {
                    LOG.log(Level.INFO, "시세 전달 받는 쪽 연결 끝({0}): {1}", client, e.getClass().getSimpleName());
                }
            } finally {
                timeout.cancel(false);
                subscribed = false;
                subscribers.remove(this);
                closeQuietly(channel);
            }
        }

        private void writeLine(String text) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap((text + "\n").getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
        }

        /**
         * 받는 쪽이 연결을 닫았는지 본다. 구독 뒤에는 받는 쪽이 보낼 것이 없으므로 읽기가 끝나거나(-1) 무엇이든 오면 닫는다. 장 밖처럼
         * 보낼 이벤트가 없을 때도 끊긴 받는 쪽이 자리(상한 {@value #MAX_SUBSCRIBERS})를 차지하지 않게 한다.
         */
        private void watchClose() {
            try {
                channel.read(ByteBuffer.allocate(1));
            } catch (IOException | RuntimeException ignored) {
                // 닫힘
            }
            stop();
        }

        /** 연결을 닫는다. 쓰기 중이면 그 쓰기가 예외로 끝나고 스레드가 정리한다. */
        void stop() {
            subscribed = false;
            closeQuietly(channel);
            queue.clear();
            queue.offer(STOP);
        }
    }

    /** {@code \n}까지 읽는다. 상한을 넘거나 끝나면 예외. */
    static String readLine(SocketChannel ch, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (true) {
            one.clear();
            if (ch.read(one) < 0) {
                throw new IOException("연결이 끊겼습니다");
            }
            byte b = one.get(0);
            if (b == '\n') {
                return out.toString(StandardCharsets.UTF_8);
            }
            if (out.size() >= max) {
                throw new IOException("줄이 너무 깁니다");
            }
            out.write(b);
        }
    }

    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return Thread.ofPlatform().name(name).daemon(true).factory();
    }

    private record Item(String sourceId, MarketEvent event) {}

    /** 테스트용: 연결된 받는 쪽 이름. */
    List<String> clients() {
        return subscribers.stream().filter(s -> s.subscribed).map(s -> s.client).toList();
    }
}
