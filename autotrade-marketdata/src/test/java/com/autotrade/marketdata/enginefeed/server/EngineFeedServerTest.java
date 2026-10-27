package com.autotrade.marketdata.enginefeed.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Heartbeat;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.HeartbeatLine;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Interest;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Rejection;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Status;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.StatusLine;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol.Unserved;
import com.autotrade.marketdata.enginefeed.EngineFeedSocket;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 게이트웨이 → 실전 엔진 전달 서버(ADR-016 2.2~2.5, 5장 실패 테스트 1~5·10·16·17·21·24, architect PR #245 N6·N7). 엔진 쪽은
 * 줄을 직접 주고받는 가짜 엔진으로 흉내 낸다(보내는 줄을 시험이 정확히 정하려고).
 */
class EngineFeedServerTest {

    private static final Instant T = Instant.parse("2026-10-27T00:00:01Z");
    private static final Clock CLOCK = Clock.fixed(T.plusSeconds(60), ZoneOffset.UTC);
    private static final Symbol A = Symbol.krx("005930");
    private static final Symbol B = Symbol.krx("000660");
    private static final Symbol C = Symbol.krx("035420");
    private static final EngineFeedServer.Settings FAST = new EngineFeedServer.Settings(1_000, Duration.ofMillis(500),
            Duration.ofMillis(500), Duration.ofMillis(100), Duration.ofMillis(600), Duration.ofMillis(100));

    @TempDir
    Path dir;

    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<String> sinkCalls = new CopyOnWriteArrayList<>();

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

    private Path socket() {
        return EngineFeedSocket.path(dir);
    }

    private EngineFeedServer server(EngineFeedServer.Settings settings) throws IOException {
        return keep(EngineFeedServer.open(socket(), CLOCK,
                (protect, entry) -> sinkCalls.add(protect + "|" + entry), settings));
    }

    private static TradeTick tick(Symbol s, long srcSeq) {
        return new TradeTick(s, srcSeq, T, T.plusMillis(srcSeq), new BigDecimal("60000"), 1);
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

    private static SortedSet<Symbol> set(Symbol... s) {
        return new TreeSet<>(List.of(s));
    }

    /** 줄을 직접 주고받는 가짜 엔진. 받은 줄은 따로 도는 스레드가 큐에 쌓는다. */
    private final class Engine implements AutoCloseable {
        final SocketChannel ch;
        final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        volatile boolean ended;
        Thread reader;

        Engine(boolean read) throws IOException {
            ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(socket()));
            keep(this);
            if (read) {
                startReading();
            }
        }

        void startReading() {
            reader = Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch),
                            StandardCharsets.UTF_8));
                    String l;
                    while ((l = in.readLine()) != null) {
                        lines.add(l);
                    }
                } catch (IOException ignored) {
                    // 닫힘
                }
                ended = true;
            });
        }

        void send(String line) throws IOException {
            ByteBuffer b = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
            while (b.hasRemaining()) {
                ch.write(b);
            }
        }

        Engine hello() throws IOException {
            send(EngineFeedProtocol.engineHelloLine("test-boot"));
            return this;
        }

        void interest(long rev, Set<Symbol> protect, Set<Symbol> entry) throws IOException {
            send(EngineFeedProtocol.interestLine(new Interest(rev, new TreeSet<>(protect), new TreeSet<>(entry))));
        }

        String next(Predicate<String> match) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                String l = lines.poll(50, TimeUnit.MILLISECONDS);
                if (l != null && match.test(l)) {
                    return l;
                }
            }
            throw new AssertionError("기다린 줄이 오지 않았습니다");
        }

        Status status() throws InterruptedException {
            return ((StatusLine) EngineFeedProtocol.parseGatewayControl(tree(next(l -> l.startsWith("{\"status\""))))).status();
        }

        Heartbeat heartbeat() throws InterruptedException {
            return ((HeartbeatLine) EngineFeedProtocol.parseGatewayControl(tree(next(l -> l.startsWith("{\"hb\""))))).heartbeat();
        }

        RecordingCodec.Line event() throws InterruptedException {
            return new RecordingCodec().decode(next(l -> l.startsWith("{\"seq\"")));
        }

        /** 지금까지 쌓인 이벤트 줄(기다리지 않음). */
        List<RecordingCodec.Line> drainEvents() {
            List<String> all = new ArrayList<>();
            lines.drainTo(all);
            RecordingCodec codec = new RecordingCodec();
            return all.stream().filter(l -> l.startsWith("{\"seq\"")).map(codec::decode).toList();
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    private static JsonNode tree(String line) {
        return EngineFeedProtocol.readTree(line);
    }

    @Test
    void 인사_뒤_게이트웨이_인사와_rev_0_상태_줄이_오고_관심은_구독_계획에_넘어간다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine e = new Engine(true).hello();
        assertThat(e.next(l -> true)).isEqualTo(EngineFeedProtocol.gatewayHelloLine());
        Status first = e.status();
        assertThat(first.rev()).isZero();
        assertThat(first.served()).isEmpty();
        await(server::engineConnected);

        e.interest(1, Set.of(A), Set.of(B));
        Status applied = e.status();
        assertThat(applied.rev()).isEqualTo(1);
        // 배정 소식이 없으면 PENDING
        assertThat(applied.unserved()).isEqualTo(Map.of(A, Unserved.PENDING, B, Unserved.PENDING));
        assertThat(sinkCalls).containsExactly("[KRX:005930]|[KRX:000660]");

        // 배정이 바뀌면 상태 줄. served는 PENDING을 이긴다
        server.assignment(Map.of(A, "KIWOOM-WS"), Map.of(B, Unserved.CAPACITY));
        Status assigned = e.status();
        assertThat(assigned.rev()).isEqualTo(1);
        assertThat(assigned.served()).isEqualTo(Map.of(A, "KIWOOM-WS"));
        assertThat(assigned.unserved()).isEqualTo(Map.of(B, Unserved.CAPACITY));
        assertThat(assigned.rejected()).isNull();
    }

    @Test
    void 두_번째_연결은_인사_없이_닫히고_첫_연결은_계속_받는다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine first = new Engine(true).hello();
        first.interest(1, Set.of(A), Set.of());
        first.status();
        await(server::engineConnected);

        Engine second = new Engine(true);
        try {
            second.hello();
        } catch (IOException closedFirst) {
            // 서버가 먼저 닫았다
        }
        await(() -> second.ended);
        assertThat(second.lines).isEmpty();

        server.publish("KIWOOM-WS", tick(A, 7));
        RecordingCodec.Line got = first.event();
        assertThat(got.seq()).isEqualTo(1);
        assertThat(got.sourceId()).isEqualTo("KIWOOM-WS");
        assertThat(got.sourceSequence()).isEqualTo(7);
    }

    @Test
    void 관심은_전체_교체이고_같은_줄을_두_번_보내도_결과가_같다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine e = new Engine(true).hello();
        e.status();
        e.interest(1, Set.of(A, B), Set.of());
        Status two = e.status();
        assertThat(two.unserved().keySet()).containsExactly(B, A);

        e.interest(2, Set.of(A), Set.of());
        Status one = e.status();
        assertThat(one.unserved().keySet()).containsExactly(A);
        server.publish("KIWOOM-WS", tick(B, 1)); // 빠진 종목
        server.publish("KIWOOM-WS", tick(A, 2));
        RecordingCodec.Line got = e.event();
        assertThat(got.event().symbol()).isEqualTo(A);

        // 같은 내용을 rev만 올려 다시 보내면 상태 줄 결과(served·unserved)가 같다
        e.interest(3, Set.of(A), Set.of());
        Status again = e.status();
        assertThat(again.unserved()).isEqualTo(one.unserved());
        assertThat(again.served()).isEqualTo(one.served());
        assertThat(sinkCalls).containsExactly("[KRX:000660, KRX:005930]|[]", "[KRX:005930]|[]", "[KRX:005930]|[]");
    }

    @Test
    void 상한_형식_오류_관심은_앞_관심을_두고_rejected로_답하고_줄어든_rev는_무시한다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine e = new Engine(true).hello();
        e.status();
        e.interest(5, Set.of(A), Set.of());
        e.status();

        StringBuilder tooMany = new StringBuilder("{\"interest\":{\"rev\":6,\"protect\":[");
        for (int i = 1; i <= 65; i++) {
            tooMany.append(i > 1 ? "," : "").append("\"KRX:").append(String.format("%06d", i)).append('"');
        }
        e.send(tooMany.append("],\"entry\":[]}}").toString());
        Status rejected = e.status();
        assertThat(rejected.rejected()).isEqualTo(Rejection.TOO_MANY_PROTECT);
        assertThat(rejected.rev()).isEqualTo(5);
        assertThat(rejected.unserved().keySet()).containsExactly(A);

        e.send("{\"interest\":{\"rev\":7,\"protect\":[\"KRX:005930\"],\"entry\":[],\"price\":\"1\"}}");
        assertThat(e.status().rejected()).isEqualTo(Rejection.BAD_FORMAT);
        e.send("{\"x\":\"" + "a".repeat(20_000) + "\"}");
        assertThat(e.status().rejected()).isEqualTo(Rejection.LINE_TOO_LONG);
        e.send(EngineFeedProtocol.engineHelloLine("again"));
        assertThat(e.status().rejected()).isEqualTo(Rejection.UNEXPECTED_LINE);

        // 줄어들거나 같은 rev는 무시: 상태 줄도 구독 계획 호출도 없다
        e.interest(4, Set.of(B), Set.of());
        e.interest(5, Set.of(B), Set.of());
        e.interest(8, Set.of(A, C), Set.of());
        Status after = e.status();
        assertThat(after.rev()).isEqualTo(8);
        assertThat(after.unserved().keySet()).containsExactly(A, C);
        assertThat(sinkCalls).containsExactly("[KRX:005930]|[]", "[KRX:005930, KRX:035420]|[]");
        // 관심 밖 종목은 여전히 오지 않는다(거부된 관심은 적용되지 않음)
        server.publish("K", tick(B, 1));
        server.publish("K", tick(C, 2));
        assertThat(e.event().event().symbol()).isEqualTo(C);
    }

    @Test
    void 엔진이_읽지_않아도_publish는_기다리지_않고_다시_읽으면_종목별_공백이_먼저_오며_멈춤이_길면_끊는다() throws Exception {
        EngineFeedServer.Settings small = new EngineFeedServer.Settings(50, Duration.ofMillis(500), Duration.ofSeconds(30),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
        EngineFeedServer server = server(small);
        Engine e = new Engine(false).hello();
        e.interest(1, Set.of(A, B), Set.of());
        await(server::engineConnected);
        Thread.sleep(200); // 관심 적용

        long start = System.nanoTime();
        for (int i = 1; i <= 200_000; i++) {
            server.publish("KIWOOM-WS", tick(i % 2 == 0 ? A : B, i));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).as("게이트웨이 수신 스레드는 기다리지 않는다").isLessThan(10_000);
        assertThat(server.droppedCount()).isPositive();

        e.startReading();
        // 다시 읽기 시작하면 큐가 비고, 버린 종목마다 다음 이벤트 앞에 공백이 한 번 온다
        List<String> got = new CopyOnWriteArrayList<>();
        long seq = 300_000;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!(got.stream().anyMatch(l -> l.contains("\"GAP\"") && l.contains("KRX:005930"))
                && got.stream().anyMatch(l -> l.contains("\"GAP\"") && l.contains("KRX:000660")))
                && System.nanoTime() < deadline) {
            server.publish("KIWOOM-WS", tick(A, ++seq));
            server.publish("KIWOOM-WS", tick(B, ++seq));
            Thread.sleep(20);
            e.lines.drainTo(got);
        }
        List<RecordingCodec.Line> gaps = got.stream().filter(l -> l.contains("\"GAP\""))
                .map(l -> new RecordingCodec().decode(l)).toList();
        assertThat(gaps).isNotEmpty();
        assertThat(gaps).allSatisfy(g -> {
            assertThat(g.sourceId()).isEqualTo(EngineFeedServer.SOURCE_ID);
            assertThat(((DataGap) g.event()).reason()).isEqualTo(DataGap.GapReason.CONSUMER_OVERFLOW);
        });
        assertThat(gaps.stream().map(g -> g.event().symbol()).distinct()).containsExactlyInAnyOrder(A, B);
        // 이벤트 줄 순번(seq)은 버린 것과 상관없이 빈틈 없다
        List<Long> seqs = got.stream().filter(l -> l.startsWith("{\"seq\""))
                .map(l -> new RecordingCodec().decode(l).seq()).toList();
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(seqs.get(i - 1) + 1);
        }
    }

    @Test
    void 한_줄_쓰기가_멈춤_한도를_넘으면_연결을_끊는다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine e = new Engine(false).hello();
        e.interest(1, Set.of(A), Set.of());
        await(server::engineConnected);
        Thread.sleep(200);
        // 큰 호가 줄로 소켓 버퍼를 채워 쓰기가 멈추게 한다
        List<com.autotrade.domain.market.PriceLevel> asks = new ArrayList<>();
        List<com.autotrade.domain.market.PriceLevel> bids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            asks.add(new com.autotrade.domain.market.PriceLevel(new BigDecimal(60000 + i), 1000 + i));
            bids.add(new com.autotrade.domain.market.PriceLevel(new BigDecimal(59999 - i), 1000 + i));
        }
        for (int i = 1; i <= 900; i++) {
            server.publish("K", new com.autotrade.domain.market.OrderBookSnapshot(A, i, T, T, asks, bids));
        }
        await(() -> !server.engineConnected());
    }

    @Test
    void 생존_줄은_주기마다_오고_소스별_상태와_시세_수신_시각을_싣는다() throws Exception {
        EngineFeedServer server = server(FAST);
        server.sourceState("KIWOOM-WS", ConnectionState.CONNECTED);
        server.publish("KIWOOM-WS", tick(A, 5)); // 관심 밖 종목도 lastRx에 든다
        Engine e = new Engine(true).hello();
        Heartbeat h1 = e.heartbeat();
        assertThat(h1.at()).isEqualTo(CLOCK.instant());
        assertThat(h1.sources().get("KIWOOM-WS").state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(h1.sources().get("KIWOOM-WS").lastRx()).isEqualTo(T.plusMillis(5));
        assertThat(h1.dropped()).isZero();

        // 소스 끊김은 상태 줄과 생존 줄에 반영된다
        server.sourceState("KIWOOM-WS", ConnectionState.DISCONNECTED);
        e.next(l -> l.startsWith("{\"status\"") && l.contains("DISCONNECTED"));
        await(() -> {
            try {
                return e.heartbeat().sources().get("KIWOOM-WS").state() == ConnectionState.DISCONNECTED;
            } catch (InterruptedException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    void 소스가_연결된_채_시세만_멈추면_lastRx가_멈춘다() throws Exception {
        EngineFeedServer server = server(FAST);
        server.sourceState("KIWOOM-WS", ConnectionState.CONNECTED);
        server.publish("KIWOOM-WS", tick(A, 9));
        Engine e = new Engine(true).hello();
        e.interest(1, Set.of(A), Set.of());
        // 허브가 만든 공백 줄은 시세 수신이 아니다(PING·등록 응답은 허브까지 오지 않는다)
        server.publish("KIWOOM-WS", new DataGap(A, 0, T.plusSeconds(30), T.plusSeconds(30), T, T.plusSeconds(30),
                DataGap.GapReason.SOURCE_SWITCH));
        Heartbeat later = null;
        for (int i = 0; i < 5; i++) {
            later = e.heartbeat();
        }
        assertThat(later.sources().get("KIWOOM-WS").state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(later.sources().get("KIWOOM-WS").lastRx()).isEqualTo(T.plusMillis(9));
    }

    @Test
    void 큐가_찬_동안_생존_줄은_버리고_다시_넣지_않으며_상태_줄은_다음_차례에_보낸다() throws Exception {
        EngineFeedServer.Settings tiny = new EngineFeedServer.Settings(20, Duration.ofMillis(500), Duration.ofSeconds(30),
                Duration.ofMillis(100), Duration.ofSeconds(30), Duration.ofSeconds(30));
        EngineFeedServer server = server(tiny);
        Engine e = new Engine(false).hello();
        e.interest(1, Set.of(A), Set.of());
        await(server::engineConnected);
        Thread.sleep(300);
        // 큰 줄로 소켓 버퍼와 큐를 채운다
        List<com.autotrade.domain.market.PriceLevel> asks = new ArrayList<>();
        List<com.autotrade.domain.market.PriceLevel> bids = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            asks.add(new com.autotrade.domain.market.PriceLevel(new BigDecimal(60000 + i), 1000 + i));
            bids.add(new com.autotrade.domain.market.PriceLevel(new BigDecimal(59999 - i), 1000 + i));
        }
        for (int i = 1; i <= 2_000; i++) {
            server.publish("K", new com.autotrade.domain.market.OrderBookSnapshot(A, i, T, T, asks, bids));
        }
        await(() -> server.heartbeatsDroppedForTest() >= 3);
        server.assignment(Map.of(A, "K"), Map.of()); // 큐가 차 있어 상태 줄은 밀린다

        e.startReading();
        // 결국 served 상태 줄이 온다(그때 상태로 다시 넣음)
        await(() -> e.lines.stream().anyMatch(l -> l.startsWith("{\"status\"") && l.contains("\"served\":{\"KRX:005930\":\"K\"}")));
    }

    @Test
    void 끊긴_뒤_유지_시간_안에_다시_붙어_rev_1을_보내면_받고_그_전_상태_줄은_rev_0이다() throws Exception {
        EngineFeedServer.Settings slow = new EngineFeedServer.Settings(1_000, Duration.ofMillis(500), Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(30));
        EngineFeedServer server = server(slow);
        Engine first = new Engine(true).hello();
        first.status();
        first.interest(9, Set.of(A), Set.of());
        first.status();
        server.assignment(Map.of(A, "K"), Map.of());
        first.status();
        first.close();
        await(() -> !server.engineConnected());

        Engine second = new Engine(true).hello();
        Status before = second.status();
        // 앞 연결의 관심으로 나간 served지만 이 연결에서 관심을 아직 적용하지 않아 rev 0
        assertThat(before.rev()).isZero();
        assertThat(before.served()).isEqualTo(Map.of(A, "K"));
        second.interest(1, Set.of(A, B), Set.of());
        Status after = second.status();
        assertThat(after.rev()).isEqualTo(1);
        assertThat(after.served()).isEqualTo(Map.of(A, "K"));
        assertThat(after.unserved()).isEqualTo(Map.of(B, Unserved.PENDING));
        // 유지 시간 안이라 빈 관심(구독 해제)은 넘기지 않았다
        assertThat(sinkCalls).containsExactly("[KRX:005930]|[]", "[KRX:000660, KRX:005930]|[]");
    }

    @Test
    void 유지_시간이_지나도_새_관심이_없으면_빈_관심을_넘긴다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine first = new Engine(true).hello();
        first.interest(1, Set.of(A), Set.of(B));
        first.status();
        first.status();
        first.close();
        await(() -> sinkCalls.size() == 2);
        assertThat(sinkCalls).containsExactly("[KRX:005930]|[KRX:000660]", "[]|[]");
        assertThat(server.engineConnected()).isFalse();
    }

    @Test
    void 인사가_시간_안에_오지_않으면_끊는다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine silent = new Engine(true);
        await(() -> silent.ended);
        assertThat(silent.lines).isEmpty();
        // 자리가 풀려 다음 엔진이 붙는다
        Engine next = new Engine(true).hello();
        next.status();
        assertThat(server.engineConnected()).isTrue();
    }

    @Test
    void publish_안의_예외는_밖으로_나가지_않는다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine e = new Engine(true).hello();
        e.interest(1, Set.of(A), Set.of());
        e.status();
        server.publish("KIWOOM-WS", null);
        server.publish(null, tick(A, 1));
        assertThat(server.publishErrorCount()).isEqualTo(2);
        // 그 뒤 이벤트는 그대로 간다
        server.publish("KIWOOM-WS", tick(A, 2));
        assertThat(e.event().sourceSequence()).isEqualTo(2);
    }

    @Test
    void 소켓_파일이_지워지면_다시_만들고_붙어_있던_연결은_유지한다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine first = new Engine(true).hello();
        first.interest(1, Set.of(A), Set.of());
        first.status();
        Files.delete(socket());
        await(() -> Files.exists(socket()));
        server.publish("K", tick(A, 3));
        assertThat(first.event().sourceSequence()).isEqualTo(3);

        // 첫 연결이 끝나면 다시 만든 소켓으로 새 엔진이 붙는다
        first.close();
        await(() -> !server.engineConnected());
        Engine second = new Engine(true).hello();
        second.status();
        await(server::engineConnected);
    }

    @Test
    void run_폴더째_지워져도_다시_만든다() throws Exception {
        server(FAST);
        Path run = socket().getParent();
        for (int i = 0; ; i++) {
            try {
                Files.deleteIfExists(socket());
                Files.delete(run);
                break;
            } catch (java.nio.file.DirectoryNotEmptyException raced) {
                if (i > 20) {
                    throw raced; // 확인 타이머가 그 사이 다시 만들었다
                }
            }
        }
        await(() -> Files.exists(socket()));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(run))).isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket()))).isEqualTo("rw-------");
        }
        Engine e = new Engine(true).hello();
        e.status();
    }

    @Test
    void 다른_파일이_소켓_자리를_차지하면_빼앗지_않는다() throws Exception {
        EngineFeedServer server = server(FAST);
        Files.delete(socket());
        Files.writeString(socket(), "not mine");
        Thread.sleep(400);
        assertThat(Files.readString(socket())).isEqualTo("not mine");
        server.close();
        assertThat(Files.readString(socket())).isEqualTo("not mine");
    }

    @Test
    void 다른_프로세스가_듣고_있으면_열지_않는다() throws Exception {
        server(FAST);
        assertThatThrownBy(() -> EngineFeedServer.open(socket(), CLOCK, (p, e) -> {}))
                .isInstanceOf(IOException.class).hasMessageContaining("이미");
    }

    @Test
    void 이벤트_줄_순번은_연결마다_1부터_빈틈_없고_녹화_형식이다() throws Exception {
        EngineFeedServer server = server(FAST);
        Engine e = new Engine(true).hello();
        e.interest(1, Set.of(A, B), Set.of());
        e.status();
        List<MarketEvent> sent = List.of(tick(A, 11), tick(B, 12), tick(A, 13));
        sent.forEach(ev -> server.publish("KIWOOM-WS", ev));
        RecordingCodec codec = new RecordingCodec();
        for (int i = 0; i < sent.size(); i++) {
            RecordingCodec.Line got = e.event();
            assertThat(got.seq()).isEqualTo(i + 1);
            assertThat(codec.encode(got.seq(), got.sourceId(), com.autotrade.marketdata.recording.EventSequences
                    .withSequence(got.event(), got.sourceSequence())))
                    .isEqualTo(codec.encode(i + 1, "KIWOOM-WS", sent.get(i)));
        }
    }
}
