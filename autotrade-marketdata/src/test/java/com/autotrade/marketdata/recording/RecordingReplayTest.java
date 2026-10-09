package com.autotrade.marketdata.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 2단계 완료 기준: 하루 녹화분을 두 번 리플레이하면 이벤트 순서와 값이 같다(MD-06). */
class RecordingReplayTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant OPEN = Instant.parse("2026-10-08T00:00:00Z"); // 09:00 KST
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");
    private static final Clock CLOCK = Clock.fixed(OPEN, ZoneOffset.UTC);

    @TempDir
    Path root;

    /** 두 소스(키움, 한투)가 섞여 들어오고 끊김 표시와 VI가 있는 하루. */
    static List<Object[]> scriptedDay() {
        List<Object[]> in = new ArrayList<>();
        long kiwoom = 0;
        long kis = 0;
        for (int i = 0; i < 500; i++) {
            Instant t = OPEN.plusMillis(i * 37L);
            BigDecimal px = new BigDecimal(71000 + (i % 13) * 100);
            if (i % 3 == 0) {
                in.add(new Object[] {"KIS-WS", new TradeTick(HYNIX, ++kis, t, t.plusMillis(5), new BigDecimal("120000.00"), 1 + i % 7)});
            } else if (i % 5 == 0) {
                in.add(new Object[] {"KIWOOM-WS", new OrderBookSnapshot(SAMSUNG, ++kiwoom, t, t.plusMillis(3),
                        List.of(new PriceLevel(px.add(BigDecimal.valueOf(100)), 10), new PriceLevel(px.add(BigDecimal.valueOf(200)), 20)),
                        List.of(new PriceLevel(px, 30)))});
            } else {
                in.add(new Object[] {"KIWOOM-WS", new TradeTick(SAMSUNG, ++kiwoom, t, t.plusMillis(2), px, 1 + i % 11)});
            }
            if (i == 200) {
                in.add(new Object[] {"KIWOOM-WS", new DataGap(SAMSUNG, ++kiwoom, t, t, t.minusSeconds(4), t,
                        DataGap.GapReason.DISCONNECTED)});
            }
            if (i == 300) {
                in.add(new Object[] {"KIS-WS", new InstrumentStatusChanged(HYNIX, ++kis, t, t,
                        InstrumentStatus.VOLATILITY_INTERRUPTION)});
            }
        }
        return in;
    }

    private MarketEventRecorder recorder() {
        return new MarketEventRecorder(new RecordingLayout(root), Market.KRX, SEOUL, CLOCK,
                Map.of("KIWOOM-WS", "KRX_REALTIME", "KIS-WS", "KRX_REALTIME"), "본인 연구용, 외부 공유 금지", 10_000,
                Duration.ofMillis(50));
    }

    private Path record(List<Object[]> in) {
        MarketEventRecorder rec = recorder();
        for (Object[] row : in) {
            rec.offer((String) row[0], (MarketEvent) row[1]);
        }
        rec.close();
        return new RecordingLayout(root).dayDir(Market.KRX, LocalDate.of(2026, 10, 8));
    }

    private static List<MarketEvent> replay(Path dir) {
        RecordedReplaySource src = new RecordedReplaySource(RecordedDay.open(dir));
        List<MarketEvent> out = new ArrayList<>();
        src.subscribe(Set.of(SAMSUNG, HYNIX), out::add);
        src.replay();
        return out;
    }

    @Test
    void 같은_녹화일을_두_번_리플레이하면_순서와_값이_완전히_같다() {
        Path dir = record(scriptedDay());
        List<MarketEvent> first = replay(dir);
        List<MarketEvent> second = replay(dir);
        assertThat(first).hasSize(502);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 리플레이는_받은_순서와_값을_보존하고_순번은_녹화_순번이다() {
        List<Object[]> in = scriptedDay();
        List<MarketEvent> out = replay(record(in));
        for (int i = 0; i < in.size(); i++) {
            MarketEvent original = (MarketEvent) in.get(i)[1];
            MarketEvent replayed = out.get(i);
            assertThat(replayed.sequence()).isEqualTo(i + 1);
            assertThat(EventSequences.withSequence(replayed, original.sequence())).isEqualTo(original);
        }
    }

    @Test
    void 메타에_출처와_건수와_해시가_남는다() {
        Path dir = record(scriptedDay());
        RecordingMeta meta = RecordedDay.open(dir).meta().orElseThrow();
        assertThat(meta.complete()).isTrue();
        assertThat(meta.eventCount()).isEqualTo(502);
        assertThat(meta.lastSeq()).isEqualTo(502);
        assertThat(meta.gapCount()).isEqualTo(1);
        assertThat(meta.sources()).containsEntry("KIWOOM-WS", "KRX_REALTIME").containsEntry("KIS-WS", "KRX_REALTIME");
        assertThat(meta.license()).contains("외부 공유 금지");
        assertThat(meta.eventsSha256()).hasSize(64);
        assertThat(meta.segments()).hasSize(1);
    }

    @Test
    void 일부_종목만_구독하면_그_종목만_받는다() {
        RecordedReplaySource src = new RecordedReplaySource(RecordedDay.open(record(scriptedDay())));
        assertThat(src.events(Set.of(HYNIX))).allSatisfy(e -> assertThat(e.symbol()).isEqualTo(HYNIX)).hasSize(168);
        assertThat(src.sourceId()).isEqualTo("REPLAY-KRX-2026-10-08");
    }

    @Test
    void 파일이_바뀌면_해시_검사로_거부한다() throws IOException {
        Path dir = record(scriptedDay());
        Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
        byte[] bytes = Files.readAllBytes(events);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(events, bytes);
        assertThatThrownBy(() -> RecordedDay.open(dir)).isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256");
    }

    @Test
    void 같은_날_재기동하면_순번을_이어_쓰고_구간을_나눈다() {
        List<Object[]> in = scriptedDay();
        Path dir = record(in.subList(0, 100));
        record(in.subList(100, in.size()));
        RecordedDay day = RecordedDay.open(dir);
        assertThat(day.lastSeq()).isEqualTo(502);
        assertThat(day.meta().orElseThrow().segments()).hasSize(2)
                .extracting(RecordingMeta.Segment::firstSeq).containsExactly(1L, 101L);
        assertThat(replay(dir)).isEqualTo(replay(dir));
    }

    @Test
    void 비정상_종료로_잘린_마지막_줄은_버리고_표시한다() throws IOException {
        Path dir = root.resolve("KRX/2026-10-08");
        Files.createDirectories(dir);
        RecordingCodec codec = new RecordingCodec();
        Instant t = OPEN;
        String ok1 = codec.encode(1, "KIWOOM-WS", new TradeTick(SAMSUNG, 1, t, t, new BigDecimal("71500"), 1));
        String ok2 = codec.encode(2, "KIWOOM-WS", new TradeTick(SAMSUNG, 2, t, t, new BigDecimal("71600"), 1));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(RecordingLayout.EVENTS_FILE)), true)) {
            out.write((ok1 + "\n" + ok2 + "\n" + ok2.substring(0, 20)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        }
        RecordedDay day = RecordedDay.open(dir);
        assertThat(day.truncated()).isTrue();
        assertThat(day.lines()).hasSize(2);
    }

    @Test
    void 순번이_끊긴_녹화는_손상으로_본다() throws IOException {
        Path dir = root.resolve("KRX/2026-10-08");
        Files.createDirectories(dir);
        RecordingCodec codec = new RecordingCodec();
        String l1 = codec.encode(1, "K", new TradeTick(SAMSUNG, 1, OPEN, OPEN, BigDecimal.TEN, 1));
        String l3 = codec.encode(3, "K", new TradeTick(SAMSUNG, 2, OPEN, OPEN, BigDecimal.TEN, 1));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(RecordingLayout.EVENTS_FILE)))) {
            out.write((l1 + "\n" + l3 + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> RecordedDay.open(dir)).hasMessageContaining("순번");
    }

    @Test
    void 녹화_큐가_넘치면_기다리지_않고_버린_뒤_누락을_표시한다() throws InterruptedException {
        // 쓰기 스레드를 문 앞에 세워 두고 몰아서 넣는다: 큐 2칸이 차면 나머지는 버려진다(스레드 일정과 무관)
        CountDownLatch gate = new CountDownLatch(1);
        MarketEventRecorder rec = new MarketEventRecorder(new RecordingLayout(root), Market.KRX, SEOUL, CLOCK, Map.of(), "", 2,
                Duration.ofMillis(50), task -> {
                    Thread t = new Thread(() -> {
                        try {
                            gate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        task.run();
                    }, "recorder-test");
                    t.setDaemon(true);
                    return t;
                });
        int n = 100;
        for (int i = 0; i < n; i++) {
            Instant t = OPEN.plusMillis(i);
            rec.offer("K", new TradeTick(SAMSUNG, i + 1, t, t, BigDecimal.TEN, 1));
        }
        assertThat(rec.acceptedCount()).isEqualTo(2);
        assertThat(rec.droppedCount()).isEqualTo(n - 2);

        gate.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (rec.queued() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(rec.queued()).isZero();
        // 큐가 비면 다음 이벤트 앞에 버린 구간이 누락으로 남는다
        Instant next = OPEN.plusMillis(n);
        rec.offer("K", new TradeTick(SAMSUNG, n + 1, next, next, BigDecimal.TEN, 1));
        rec.close();

        RecordedDay day = RecordedDay.open(new RecordingLayout(root).dayDir(Market.KRX, LocalDate.of(2026, 10, 8)));
        List<DataGap> gaps = day.lines().stream().map(l -> l.event()).filter(DataGap.class::isInstance)
                .map(DataGap.class::cast).toList();
        assertThat(gaps).singleElement().satisfies(g -> {
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.RECORDER_OVERFLOW);
            assertThat(g.gapStart()).isEqualTo(OPEN.plusMillis(2)); // 처음 버린 이벤트
            assertThat(g.gapEnd()).isEqualTo(next);
        });
        assertThat(day.eventCount()).isEqualTo(3 + 1);           // 받은 3건 + 누락 표시 1건
        assertThat(rec.acceptedCount()).isEqualTo(3);
    }

    @Test
    void 다른_시장_이벤트는_거절한다() {
        MarketEventRecorder rec = recorder();
        assertThatThrownBy(() -> rec.offer("K", new TradeTick(Symbol.us("AAPL"), 1, OPEN, OPEN, BigDecimal.TEN, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        rec.close();
    }
}
