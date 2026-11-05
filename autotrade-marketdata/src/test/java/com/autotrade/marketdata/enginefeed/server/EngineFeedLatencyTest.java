package com.autotrade.marketdata.enginefeed.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.enginefeed.EngineFeedClient;
import com.autotrade.marketdata.enginefeed.EngineFeedClients;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol;
import com.autotrade.marketdata.enginefeed.EngineFeedSocket;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.feed.FeedSocket;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import java.math.BigDecimal;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 지연 합격 기준(ADR-016 2.5, QA PR #87 R1, 5장 6번). 읽지 않는 실험실 받는 쪽 4개와 녹화가 함께 도는 조건에서 게이트웨이 수신
 * 콜백 순서(엔진 전달 → 녹화 → 실험실 전달)를 흉내 내고 ① 엔진 {@code publish} 한 번 p99.9 ≤ 50µs, ② 콜백 진입 → 엔진 받는
 * 쪽 줄 해석 끝 p99 ≤ 10ms, 최대 ≤ 200ms를 잰다. 컨테이너 시험이며 사용자 PC 측정(7단계 절차서 I1)이 따로 있다.
 */
class EngineFeedLatencyTest {

    private static final Instant T = Instant.parse("2026-10-27T00:30:00Z");
    /** 관심 128종목 + 관심 밖 72종목(키움 200종목 세션 한도) `[가정]`. */
    private static final int INTEREST = 128;
    private static final int SYMBOLS = 200;
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 40_000;
    /** 초당 약 5,000건(200종목 × 초당 25건) `[가정]`. */
    private static final long INTERVAL_NANOS = 200_000;

    @TempDir
    Path dir;

    @Test
    void 읽지_않는_실험실_4개와_녹화가_있어도_엔진_전달은_기준_안이다() throws Exception {
        Clock clock = Clock.fixed(T, ZoneOffset.UTC);
        Symbol[] symbols = new Symbol[SYMBOLS];
        for (int i = 0; i < SYMBOLS; i++) {
            symbols[i] = Symbol.krx(String.format("%06d", 100_000 + i));
        }
        int total = WARMUP + MEASURED;
        long[] startedAt = new long[total + 1];
        long[] arrivedAt = new long[total + 1];
        long[] publishNanos = new long[total + 1];
        AtomicInteger arrived = new AtomicInteger();

        try (FeedServer lab = FeedServer.open(FeedSocket.path(dir));
                MarketEventRecorder recorder = new MarketEventRecorder(new RecordingLayout(dir.resolve("rec")), Market.KRX,
                        ZoneId.of("Asia/Seoul"), clock, Map.of("KIWOOM-WS", "시험"), "시험", 100_000, Duration.ofSeconds(1));
                EngineFeedServer engineFeed = EngineFeedServer.open(EngineFeedSocket.path(dir), clock, (p, e) -> {})) {
            List<SocketChannel> idleLabs = new ArrayList<>();
            for (int i = 0; i < FeedServer.MAX_SUBSCRIBERS; i++) {
                SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                ch.connect(UnixDomainSocketAddress.of(FeedSocket.path(dir)));
                ch.write(ByteBuffer.wrap("{\"subscribe\":[\"KRX\"],\"client\":\"idle\"}\n".getBytes(StandardCharsets.UTF_8)));
                idleLabs.add(ch); // 읽지 않는다
            }
            awaitTrue(() -> lab.subscriberCount() == FeedServer.MAX_SUBSCRIBERS);

            EngineFeedClient engine = EngineFeedClients.forTest(EngineFeedSocket.path(dir), "latency", new EngineFeedClient.Listener() {
                @Override
                public void onConnected() {}

                @Override
                public void onEvent(RecordingCodec.Line line) {
                    int k = (int) line.sourceSequence();
                    arrivedAt[k] = System.nanoTime();
                    arrived.incrementAndGet();
                }

                @Override
                public void onStatus(EngineFeedProtocol.Status status) {}

                @Override
                public void onHeartbeat(EngineFeedProtocol.Heartbeat heartbeat) {}

                @Override
                public void onDisconnected(String reason) {}
            }, Duration.ofMillis(50), Duration.ofSeconds(5));
            try (engine) {
                engine.start();
                awaitTrue(() -> engine.state() == ConnectionState.CONNECTED);
                TreeSet<Symbol> interest = new TreeSet<>(Arrays.asList(symbols).subList(0, INTEREST));
                engine.interest(new TreeSet<>(interest.headSet(symbols[64])), new TreeSet<>(interest.tailSet(symbols[64])));
                awaitTrue(() -> engineFeed.engineConnected());
                Thread.sleep(300);

                int expected = 0;
                long next = System.nanoTime();
                for (int k = 1; k <= total; k++) {
                    while (System.nanoTime() < next) {
                        Thread.onSpinWait();
                    }
                    next += INTERVAL_NANOS;
                    Symbol s = symbols[k % SYMBOLS];
                    TradeTick tick = new TradeTick(s, k, T, T, new BigDecimal("60000"), 1);
                    if (k % SYMBOLS < INTEREST) {
                        expected++;
                    }
                    // 게이트웨이 수신 콜백 순서(ADR-016 2.5): 엔진 전달 → 녹화 → 실험실 전달
                    long t0 = System.nanoTime();
                    startedAt[k] = t0;
                    engineFeed.publish("KIWOOM-WS", tick);
                    publishNanos[k] = System.nanoTime() - t0;
                    recorder.offer("KIWOOM-WS", tick);
                    lab.publish("KIWOOM-WS", tick);
                }
                int want = expected;
                awaitTrue(() -> arrived.get() >= want - engineFeed.droppedCount());
                assertThat(engineFeed.droppedCount()).as("엔진 큐에서 버린 이벤트").isZero();
                assertThat(lab.droppedCount()).as("실험실 받는 쪽은 읽지 않아 버린다(조건 확인)").isPositive();
            }
            for (SocketChannel ch : idleLabs) {
                ch.close();
            }
        }

        long[] pub = new long[MEASURED];
        List<Long> e2e = new ArrayList<>();
        for (int k = WARMUP + 1, i = 0; k <= total; k++, i++) {
            pub[i] = publishNanos[k];
            if (k % SYMBOLS < INTEREST) {
                e2e.add(arrivedAt[k] - startedAt[k]);
            }
        }
        Arrays.sort(pub);
        long[] lat = e2e.stream().mapToLong(Long::longValue).sorted().toArray();
        long pubP999 = pct(pub, 0.999);
        long latP99 = pct(lat, 0.99);
        long latMax = lat[lat.length - 1];
        System.out.printf("엔진 전달 지연: publish p50=%dns p99=%dns p99.9=%dns 최대=%dns, 콜백→해석 p50=%dµs p99=%dµs 최대=%dµs (%d건)%n",
                pct(pub, 0.5), pct(pub, 0.99), pubP999, pub[pub.length - 1], pct(lat, 0.5) / 1000, latP99 / 1000,
                latMax / 1000, lat.length);
        assertThat(pubP999).as("엔진 publish p99.9 ≤ 50µs").isLessThanOrEqualTo(50_000);
        assertThat(latP99).as("콜백 진입 → 엔진 줄 해석 p99 ≤ 10ms").isLessThanOrEqualTo(10_000_000);
        assertThat(latMax).as("콜백 진입 → 엔진 줄 해석 최대 ≤ 200ms").isLessThanOrEqualTo(200_000_000);
    }

    private static long pct(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static void awaitTrue(java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("시간 안에 조건이 맞지 않았습니다");
            }
            Thread.sleep(10);
        }
    }
}
