package com.autotrade.marketdata.enginefeed.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.enginefeed.EngineFeedClient;
import com.autotrade.marketdata.enginefeed.EngineFeedClients;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Heartbeat;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Status;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Unserved;
import com.autotrade.marketdata.enginefeed.EngineFeedSocket;
import com.autotrade.marketdata.recording.RecordingCodec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 엔진 받는 쪽({@link EngineFeedClient})과 서버를 실제 소켓으로 잇는다(ADR-016 2.3·2.7, 5장 1·16번). */
class EngineFeedClientServerTest {

    private static final Instant T = Instant.parse("2026-10-27T00:00:01Z");
    private static final Clock CLOCK = Clock.fixed(T.plusSeconds(60), ZoneOffset.UTC);
    private static final Symbol A = Symbol.krx("005930");
    private static final Symbol B = Symbol.krx("000660");
    private static final EngineFeedServer.Settings FAST = new EngineFeedServer.Settings(1_000, Duration.ofMillis(500),
            Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofMillis(100));

    @TempDir
    Path dir;

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable c : open.reversed()) {
            c.close();
        }
    }

    private <X extends AutoCloseable> X keep(X c) {
        open.add(c);
        return c;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 조건이 맞지 않았습니다");
            }
            Thread.sleep(10);
        }
    }

    private static final Status NONE = new Status(0, T, new java.util.TreeMap<>(), new java.util.TreeMap<>(),
            new java.util.TreeMap<>(), null);

    /** 받은 것을 모으는 처리기. */
    static final class Recorder implements EngineFeedClient.Listener {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<RecordingCodec.Line> events = new CopyOnWriteArrayList<>();
        final List<Status> statuses = new CopyOnWriteArrayList<>();
        final List<Heartbeat> heartbeats = new CopyOnWriteArrayList<>();
        final List<String> disconnects = new CopyOnWriteArrayList<>();

        @Override
        public void onConnected() {
            calls.add("connected");
        }

        @Override
        public void onEvent(RecordingCodec.Line line) {
            events.add(line);
        }

        @Override
        public void onStatus(Status status) {
            statuses.add(status);
        }

        @Override
        public void onHeartbeat(Heartbeat heartbeat) {
            heartbeats.add(heartbeat);
        }

        @Override
        public void onDisconnected(String reason) {
            disconnects.add(reason);
        }

        /** 마지막 상태 줄. 아직 없으면 빈 상태(rev -1 대신 쓰지 않게 served 비교만 하는 곳에서도 안전). */
        Status last() {
            return statuses.isEmpty() ? NONE : statuses.getLast();
        }
    }

    private Path socket() {
        return EngineFeedSocket.path(dir);
    }

    private EngineFeedClient client(Recorder r) {
        return keep(EngineFeedClients.forTest(socket(), "boot-1", r, Duration.ofMillis(50), Duration.ofMillis(300)));
    }

    private static TradeTick tick(Symbol s, long srcSeq) {
        return new TradeTick(s, srcSeq, T, T, new BigDecimal("60000"), 1);
    }

    @Test
    void 관심을_보내고_served를_확인한_뒤_이벤트와_생존_줄을_받는다() throws Exception {
        List<String> sink = new CopyOnWriteArrayList<>();
        EngineFeedServer server = keep(EngineFeedServer.open(socket(), CLOCK, (p, e) -> sink.add(p + "|" + e), FAST));
        Recorder r = new Recorder();
        EngineFeedClient c = client(r);
        assertThat(c.interest(Set.of(A), Set.of(B))).as("연결 전에는 보내지 못한다").isZero();
        c.start();
        await(() -> c.state() == ConnectionState.CONNECTED);
        await(() -> !r.statuses.isEmpty() && r.last().rev() == 1);
        assertThat(sink).containsExactly("[KRX:005930]|[KRX:000660]");

        server.assignment(Map.of(A, "KIWOOM-WS"), Map.of(B, Unserved.NO_SOURCE));
        await(() -> r.last().served().equals(Map.of(A, "KIWOOM-WS")));
        assertThat(r.last().unserved()).isEqualTo(Map.of(B, Unserved.NO_SOURCE));

        server.publish("KIWOOM-WS", tick(A, 3));
        await(() -> r.events.size() == 1);
        assertThat(r.events.getFirst().seq()).isEqualTo(1);
        assertThat(r.events.getFirst().sourceSequence()).isEqualTo(3);
        await(() -> r.heartbeats.size() >= 2);

        assertThat(c.interest(Set.of(A, B), Set.of())).isEqualTo(2);
        await(() -> r.last().rev() == 2);
    }

    @Test
    void 다시_붙으면_마지막_관심을_rev_1로_다시_보내고_게이트웨이는_받는다() throws Exception {
        List<String> sink = new CopyOnWriteArrayList<>();
        EngineFeedServer first = EngineFeedServer.open(socket(), CLOCK, (p, e) -> sink.add(p + "|" + e), FAST);
        Recorder r = new Recorder();
        EngineFeedClient c = client(r);
        c.start();
        await(() -> c.state() == ConnectionState.CONNECTED);
        c.interest(Set.of(A), Set.of());
        assertThat(c.interest(Set.of(A, B), Set.of())).isEqualTo(2);
        await(() -> r.last().rev() == 2);

        first.close(); // 게이트웨이 재기동
        await(() -> !r.disconnects.isEmpty());
        assertThat(c.state()).isNotEqualTo(ConnectionState.CONNECTED);
        int before = r.statuses.size();
        keep(EngineFeedServer.open(socket(), CLOCK, (p, e) -> sink.add(p + "|" + e), FAST));
        await(() -> r.statuses.size() > before && r.last().rev() == 1);
        assertThat(r.calls).containsExactly("connected", "connected");
        assertThat(sink).containsExactly("[KRX:005930]|[]", "[KRX:000660, KRX:005930]|[]", "[KRX:000660, KRX:005930]|[]");
    }

    @Test
    void 다른_엔진이_붙어_있으면_인사_없이_끊긴_이유를_넘긴다() throws Exception {
        keep(EngineFeedServer.open(socket(), CLOCK, (p, e) -> {}, FAST));
        Recorder r1 = new Recorder();
        EngineFeedClient first = client(r1);
        first.start();
        await(() -> first.state() == ConnectionState.CONNECTED);
        Recorder r2 = new Recorder();
        EngineFeedClient second = client(r2);
        second.start();
        await(() -> !r2.disconnects.isEmpty());
        assertThat(r2.disconnects.getFirst()).contains("게이트웨이 인사 전");
        assertThat(r2.calls).isEmpty();
        assertThat(first.state()).isEqualTo(ConnectionState.CONNECTED);
    }

    @Test
    void 인사가_다르거나_오지_않으면_연결로_보지_않는다() throws Exception {
        Files.createDirectories(socket().getParent());
        try (ServerSocketChannel fake = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            fake.bind(UnixDomainSocketAddress.of(socket()));
            Recorder r = new Recorder();
            EngineFeedClient c = client(r);
            c.start();
            // 1) 녹화 형식 버전이 다른 게이트웨이
            try (SocketChannel ch = fake.accept()) {
                write(ch, "{\"feed\":\"autotrade-engine-feed\",\"formatVersion\":1,\"recordingFormat\":"
                        + (RecordingCodec.FORMAT_VERSION + 1) + "}");
                await(() -> !r.disconnects.isEmpty());
            }
            assertThat(r.disconnects.getFirst()).contains("녹화 형식 버전");
            // 2) 인사를 보내지 않는 가짜 서버: 시간 초과로 끊는다
            try (SocketChannel ch = fake.accept()) {
                assertThat(ch.isConnected()).isTrue();
                await(() -> r.disconnects.size() >= 2);
            }
            assertThat(r.calls).isEmpty();
            assertThat(c.state()).isNotEqualTo(ConnectionState.CONNECTED);
        }
    }

    @Test
    void 상한을_넘는_관심은_보내지_않고_앞_관심을_둔다() throws Exception {
        List<String> sink = new CopyOnWriteArrayList<>();
        keep(EngineFeedServer.open(socket(), CLOCK, (p, e) -> sink.add(p + "|" + e), FAST));
        Recorder r = new Recorder();
        EngineFeedClient c = client(r);
        c.start();
        await(() -> c.state() == ConnectionState.CONNECTED);
        c.interest(Set.of(A), Set.of());
        Set<Symbol> tooMany = new java.util.TreeSet<>();
        IntStream.rangeClosed(1, 65).forEach(i -> tooMany.add(Symbol.krx(String.format("%06d", i))));
        assertThatThrownBy(() -> c.interest(tooMany, Set.of())).isInstanceOf(EngineFeedProtocol.ProtocolException.class);
        await(() -> r.statuses.stream().anyMatch(s -> s.rev() == 1));
        assertThat(c.interest(Set.of(A), Set.of(B))).isEqualTo(2);
        await(() -> sink.size() == 2);
        assertThat(sink).containsExactly("[KRX:005930]|[]", "[KRX:005930]|[KRX:000660]");
    }

    private static void write(SocketChannel ch, String line) throws IOException {
        ByteBuffer b = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
        while (b.hasRemaining()) {
            ch.write(b);
        }
    }
}
