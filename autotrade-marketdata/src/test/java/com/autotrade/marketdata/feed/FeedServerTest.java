package com.autotrade.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.recording.RecordingCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 게이트웨이 → 실험실 시세 전달(ADR-010 2.1~2.2, D-076): 게이트웨이는 기다리지 않고, 못 넘긴 구간은 공백으로 남는다. */
class FeedServerTest {

    private static final Instant T = Instant.parse("2026-10-14T00:00:01Z");
    private static final Symbol SAMSUNG = Symbol.krx("005930");

    @TempDir
    Path dir;

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : open.reversed()) {
            c.close();
        }
    }

    private <T extends AutoCloseable> T keep(T c) {
        open.add(c);
        return c;
    }

    private static TradeTick tick(Symbol s, long srcSeq) {
        return new TradeTick(s, srcSeq, T, T, new BigDecimal("60000"), 1);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 조건이 맞지 않았습니다");
            }
            Thread.sleep(20);
        }
    }

    private record Got(String sourceId, MarketEvent event) {}

    private FeedClient client(Path socket, List<Got> got) {
        return keep(new FeedClient(socket, Set.of(Market.KRX), "lab", (src, e) -> got.add(new Got(src, e)),
                Clock.fixed(T.plusSeconds(60), java.time.ZoneOffset.UTC), Duration.ofMillis(50), Duration.ofMillis(200)));
    }

    @Test
    void deliversSubscribedMarketWithOriginalSourceAndSequence() throws Exception {
        Path socket = FeedSocket.path(dir);
        FeedServer server = keep(FeedServer.open(socket));
        List<Got> got = new CopyOnWriteArrayList<>();
        FeedClient c = client(socket, got);
        c.start();
        await(() -> server.subscriberCount() == 1 && c.state() == ConnectionState.CONNECTED);
        assertThat(server.clients()).containsExactly("lab");

        server.publish("KIWOOM-WS", tick(SAMSUNG, 41));
        server.publish("KIWOOM-WS", tick(Symbol.us("AAPL"), 42)); // 구독하지 않은 시장
        server.publish("KIS-WS", tick(SAMSUNG, 7));
        await(() -> got.size() == 2);

        assertThat(got).extracting(Got::sourceId).containsExactly("KIWOOM-WS", "KIS-WS");
        assertThat(got).extracting(g -> g.event().sequence()).containsExactly(41L, 7L);
        assertThat(got.get(0).event()).isEqualTo(tick(SAMSUNG, 41));
        assertThat(c.receivedCount()).isEqualTo(2);
    }

    @Test
    void socketIsOwnerOnlyAndSecondServerIsRefused() throws Exception {
        Path socket = FeedSocket.path(dir);
        keep(FeedServer.open(socket));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.getParent())))
                    .isEqualTo("rwx------");
        }
        assertThatThrownBy(() -> FeedServer.open(socket)).isInstanceOf(IOException.class);
    }

    @Test
    void closingRemovesSocketAndPublishAfterCloseIsIgnored() throws Exception {
        Path socket = FeedSocket.path(dir);
        FeedServer server = FeedServer.open(socket);
        server.close();
        assertThat(socket).doesNotExist();
        server.publish("KIWOOM-WS", tick(SAMSUNG, 1));
        // 비정상 종료로 남은 소켓 파일은 다음 게이트웨이가 지우고 연다
        Files.createFile(socket);
        keep(FeedServer.open(socket));
    }

    @Test
    void clientReconnectsAndMarksTheDisconnectedStretchAsGap() throws Exception {
        Path socket = FeedSocket.path(dir);
        FeedServer first = FeedServer.open(socket);
        List<Got> got = new CopyOnWriteArrayList<>();
        FeedClient c = client(socket, got);
        c.start();
        await(() -> c.state() == ConnectionState.CONNECTED && first.subscriberCount() == 1);
        first.publish("KIWOOM-WS", tick(SAMSUNG, 1));
        await(() -> got.size() == 1);

        first.close();
        await(() -> c.state() == ConnectionState.DISCONNECTED);
        FeedServer second = keep(FeedServer.open(socket));
        await(() -> c.reconnectCount() == 1 && second.subscriberCount() == 1);
        second.publish("KIWOOM-WS", tick(SAMSUNG, 2));
        await(() -> got.size() == 3);

        assertThat(got.get(1).sourceId()).isEqualTo(FeedClient.SOURCE_ID);
        assertThat(got.get(1).event()).isInstanceOfSatisfying(DataGap.class, g -> {
            assertThat(g.symbol()).isEqualTo(SAMSUNG);
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.DISCONNECTED);
            assertThat(g.gapStart()).isEqualTo(T);
            assertThat(g.gapEnd()).isEqualTo(T.plusSeconds(60));
        });
        assertThat(got.get(2).event().sequence()).isEqualTo(2);
    }

    @Test
    void slowSubscriberNeverBlocksPublishAndGetsOverflowGap() throws Exception {
        Path socket = FeedSocket.path(dir);
        FeedServer server = keep(FeedServer.open(socket, 3, Duration.ofSeconds(30)));
        SocketChannel raw = keep(SocketChannel.open(StandardProtocolFamily.UNIX));
        raw.connect(UnixDomainSocketAddress.of(socket));
        raw.write(ByteBuffer.wrap((FeedProtocol.subscribeLine(new FeedProtocol.Subscribe(Set.of(Market.KRX), "slow"))
                + "\n").getBytes(StandardCharsets.UTF_8)));
        await(() -> server.subscriberCount() == 1);

        long started = System.nanoTime();
        int published = 0;
        while (server.droppedCount() == 0 && published < 2_000_000) {
            server.publish("KIWOOM-WS", tick(SAMSUNG, ++published));
        }
        assertThat(server.droppedCount()).isPositive();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));

        // 이제 읽기 시작하면 쌓인 줄이 나오고, 다음 이벤트 앞에 넘기지 못한 구간 공백이 온다
        List<RecordingCodec.Line> lines = new CopyOnWriteArrayList<>();
        BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(raw), StandardCharsets.UTF_8));
        assertThat(FeedProtocol.checkHello(in.readLine())).isEmpty();
        RecordingCodec codec = new RecordingCodec();
        Thread reader = Thread.ofPlatform().daemon(true).start(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(codec.decode(line));
                }
            } catch (IOException ignored) {
                // 테스트 종료
            }
        });
        int before = published;
        await(() -> lines.size() >= before - server.droppedCount());
        server.publish("KIWOOM-WS", tick(SAMSUNG, ++published));
        await(() -> lines.stream().anyMatch(l -> l.event() instanceof DataGap));

        RecordingCodec.Line gap = lines.stream().filter(l -> l.event() instanceof DataGap).findFirst().orElseThrow();
        assertThat(gap.sourceId()).isEqualTo(FeedServer.SOURCE_ID);
        assertThat(((DataGap) gap.event()).reason()).isEqualTo(DataGap.GapReason.CONSUMER_OVERFLOW);
        assertThat(lines).extracting(RecordingCodec.Line::seq).doesNotHaveDuplicates();
        raw.close();
        reader.join(2000);
    }

    @Test
    void stalledSubscriberIsDisconnected() throws Exception {
        Path socket = FeedSocket.path(dir);
        FeedServer server = keep(FeedServer.open(socket, 3, Duration.ofMillis(300)));
        SocketChannel raw = keep(SocketChannel.open(StandardProtocolFamily.UNIX));
        raw.connect(UnixDomainSocketAddress.of(socket));
        raw.write(ByteBuffer.wrap((FeedProtocol.subscribeLine(new FeedProtocol.Subscribe(Set.of(Market.KRX), "stuck"))
                + "\n").getBytes(StandardCharsets.UTF_8)));
        await(() -> server.subscriberCount() == 1);
        // 받는 쪽이 읽지 않으니 소켓 버퍼와 큐가 찬 뒤 멈춤 판단 시간이 지나면 끊는다. 그동안 publish는 기다리지 않는다
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        long published = 0;
        while (server.subscriberCount() == 1 && System.nanoTime() < deadline) {
            server.publish("KIWOOM-WS", tick(SAMSUNG, ++published));
            if (published % 1000 == 0) {
                Thread.sleep(1);
            }
        }
        assertThat(server.subscriberCount()).isZero();
        assertThat(server.droppedCount()).isPositive();
    }

    @Test
    void quietConnectionIsNotDisconnectedOnItsFirstEvent() throws Exception {
        // 멈춤 판단은 "한 줄 쓰기가 막힌 시간"이라, 장 밖처럼 오래 조용하다가 첫 이벤트가 와도 정상 연결은 끊지 않는다(PR #87 R3)
        Path socket = FeedSocket.path(dir);
        FeedServer server = keep(FeedServer.open(socket, 3, Duration.ofMillis(300)));
        List<Got> got = new CopyOnWriteArrayList<>();
        FeedClient c = client(socket, got);
        c.start();
        await(() -> server.subscriberCount() == 1 && c.state() == ConnectionState.CONNECTED);
        Thread.sleep(1_000);
        for (int i = 1; i <= 3; i++) {
            server.publish("KIWOOM-WS", tick(SAMSUNG, i));
        }
        await(() -> got.size() == 3);
        Thread.sleep(600);
        assertThat(server.subscriberCount()).isEqualTo(1);
        assertThat(c.reconnectCount()).isZero();
    }

    @Test
    void subscriberLimitAndBadSubscribeLineAreRefused() throws Exception {
        Path socket = FeedSocket.path(dir);
        FeedServer server = keep(FeedServer.open(socket));
        List<FeedClient> clients = new ArrayList<>();
        for (int i = 0; i < FeedServer.MAX_SUBSCRIBERS; i++) {
            FeedClient c = client(socket, new CopyOnWriteArrayList<>());
            c.start();
            clients.add(c);
        }
        await(() -> server.subscriberCount() == FeedServer.MAX_SUBSCRIBERS);
        FeedClient extra = client(socket, new CopyOnWriteArrayList<>());
        extra.start();
        await(() -> !extra.lastError().isEmpty());
        assertThat(extra.state()).isNotEqualTo(ConnectionState.CONNECTED);
        assertThat(server.subscriberCount()).isEqualTo(FeedServer.MAX_SUBSCRIBERS);
        extra.close();
        clients.forEach(FeedClient::close);
        await(() -> server.subscriberCount() == 0);

        SocketChannel raw = keep(SocketChannel.open(StandardProtocolFamily.UNIX));
        raw.connect(UnixDomainSocketAddress.of(socket));
        raw.write(ByteBuffer.wrap("{\"subscribe\":[\"MARS\"],\"client\":\"x\"}\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(raw.read(ByteBuffer.allocate(16))).isEqualTo(-1);
        assertThat(server.subscriberCount()).isZero();
    }

    @Test
    void protocolRejectsMalformedLines() {
        assertThatThrownBy(() -> FeedProtocol.parseSubscribe("{\"subscribe\":[\"KRX\"],\"client\":\"Lab!\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedProtocol.parseSubscribe("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedProtocol.parseSubscribe(FeedProtocol.subscribeLine(new FeedProtocol.Subscribe(Set.of(Market.KRX),
                "lab")))).isEqualTo(new FeedProtocol.Subscribe(Set.of(Market.KRX), "lab"));
        assertThat(FeedProtocol.checkHello(FeedProtocol.helloLine())).isEmpty();
        assertThat(FeedProtocol.checkHello("{\"feed\":\"other\",\"formatVersion\":1}")).isNotEmpty();
    }
}
