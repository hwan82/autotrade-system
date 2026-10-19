package com.autotrade.marketdata.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 줄 단위 읽기 {@link RecordedDayStream}과 그것으로 다시 읽는 리플레이 소스(D-117). 읽기 규칙 자체(순번·해시·잘림·깨진 머리)는
 * {@link RecordedDay#open}이 이것을 쓰므로 {@code RecordingReplayTest}가 함께 고정한다. 여기서는 줄을 쌓지 않는 쪽만의 약속을 본다:
 * 요약이 {@link RecordedDay}와 같고, 받는 쪽이 멈추면 그 뒤를 읽지 않고, 해시가 다르면 줄을 하나도 넘기지 않고, 줄 단위
 * 리플레이 소스가 읽은 녹화일로 만든 소스와 같은 이벤트를 낸다.
 */
class RecordedDayStreamTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant OPEN = Instant.parse("2026-10-08T00:00:00Z"); // 09:00 KST
    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");

    @TempDir
    Path root;

    private Path dayDir() {
        return new RecordingLayout(root).dayDir(Market.KRX, DAY);
    }

    /** 키움·한투가 섞이고 끊김 표시가 있는 하루. {@code restartAt}이 0보다 크면 그 줄 앞에서 녹화기를 다시 띄운다(D-065 표시). */
    private Path record(int n, int restartAt) {
        MarketEventRecorder rec = recorder(OPEN);
        for (int i = 0; i < n; i++) {
            if (restartAt > 0 && i == restartAt) {
                rec.close();
                rec = recorder(OPEN.plusSeconds(3600));
            }
            Instant t = OPEN.plusMillis(i * 37L).plusSeconds(i >= restartAt && restartAt > 0 ? 3600 : 0);
            if (i % 3 == 0) {
                rec.offer("KIS-WS", new TradeTick(HYNIX, i + 1, t, t, new BigDecimal("120000"), 1 + i % 7));
            } else {
                rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, i + 1, t, t, new BigDecimal(71000 + i % 13 * 100), 1 + i % 11));
            }
            if (i == n / 2) {
                rec.offer("KIWOOM-WS", new DataGap(SAMSUNG, 10_000 + i, t, t, t.minusSeconds(4), t, DataGap.GapReason.DISCONNECTED));
            }
        }
        rec.close();
        return dayDir();
    }

    private MarketEventRecorder recorder(Instant at) {
        return new MarketEventRecorder(new RecordingLayout(root), Market.KRX, SEOUL, Clock.fixed(at, ZoneOffset.UTC),
                Map.of("KIWOOM-WS", "KRX_REALTIME", "KIS-WS", "KRX_REALTIME"), "본인 연구용, 외부 공유 금지", 10_000,
                Duration.ofMillis(50));
    }

    @Test
    void 줄_단위로_읽은_줄과_요약은_하루를_올려_읽은_것과_같다() {
        Path dir = record(300, 200);
        RecordedDay day = RecordedDay.open(dir);
        assertThat(day.meta().orElseThrow().segments()).hasSize(2);
        assertThat(day.gapCount()).isEqualTo(3); // 끊김 1 + 재기동 2종목

        List<RecordingCodec.Line> lines = new ArrayList<>();
        RecordedDayStream.Summary s = RecordedDayStream.read(dir, lines::add);

        assertThat(lines).isEqualTo(day.lines());
        assertThat(s).isEqualTo(day.summary());
        assertThat(s.eventCount()).isEqualTo(303);
        assertThat(s.lastSeq()).isEqualTo(303);
        assertThat(s.truncated()).isFalse();
        assertThat(s.unmarkedRestarts()).isEmpty();
    }

    @Test
    void 표시_없는_재기동_경계와_잘린_마지막_줄을_요약에_같게_남긴다() throws IOException {
        Path dir = dayDir();
        Files.createDirectories(dir);
        RecordingCodec codec = new RecordingCodec();
        Instant t2 = OPEN.plusSeconds(4440);
        String l1 = codec.encode(1, "K", new TradeTick(SAMSUNG, 1, OPEN, OPEN, BigDecimal.TEN, 1));
        String l2 = codec.encode(2, "K", new TradeTick(SAMSUNG, 2, t2, t2, BigDecimal.TEN, 1));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(RecordingLayout.EVENTS_FILE)), true)) {
            out.write((l1 + "\n" + l2 + "\n" + l2.substring(0, 20)).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        RecordingMeta legacy = new RecordingMeta(RecordingCodec.FORMAT_VERSION, "KRX", DAY.toString(), "Asia/Seoul",
                Map.of("K", "KRX_REALTIME"), "", List.of(new RecordingMeta.Segment(OPEN.toString(), OPEN.toString(), 1),
                        new RecordingMeta.Segment(t2.toString(), "", 2), new RecordingMeta.Segment(t2.toString(), "", 9)),
                2, 2, 0, 0, false, "");
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(dir.resolve(RecordingLayout.META_FILE).toFile(), legacy);

        RecordedDayStream.Summary s = RecordedDayStream.read(dir, l -> { });
        assertThat(s).isEqualTo(RecordedDay.open(dir).summary());
        assertThat(s.truncated()).isTrue();
        assertThat(s.eventCount()).isEqualTo(2);
        // 줄이 없는 세 번째 구간(첫 순번 9)은 표시할 자리가 없어 넣지 않는다
        assertThat(s.unmarkedRestarts()).extracting(RecordingMeta.Segment::firstSeq).containsExactly(2L);
    }

    @Test
    void 정상_종료_녹화의_해시가_다르면_줄을_하나도_넘기지_않고_거부한다() throws IOException {
        Path dir = record(50, 0);
        Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
        byte[] bytes = Files.readAllBytes(events);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(events, bytes);

        List<RecordingCodec.Line> seen = new ArrayList<>();
        assertThatThrownBy(() -> RecordedDayStream.read(dir, seen::add))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256");
        assertThat(seen).isEmpty();
    }

    @Test
    void 받는_쪽이_예외를_던지면_그대로_올라가고_그_뒤_줄은_읽지_않는다() {
        Path dir = record(50, 0);
        List<Long> seen = new ArrayList<>();
        assertThatThrownBy(() -> RecordedDayStream.read(dir, l -> {
            seen.add(l.seq());
            if (l.seq() == 3) {
                throw new IllegalArgumentException("받는 쪽 멈춤");
            }
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("받는 쪽 멈춤");
        assertThat(seen).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 메타만_읽고_형식_버전이_다르면_거부한다() throws IOException {
        Path dir = record(5, 0);
        assertThat(RecordedDayStream.readMeta(dir)).isEqualTo(RecordedDay.open(dir).meta());

        Path meta = dir.resolve(RecordingLayout.META_FILE);
        Files.writeString(meta, Files.readString(meta).replace("\"formatVersion\" : " + RecordingCodec.FORMAT_VERSION,
                "\"formatVersion\" : 99"));
        assertThatThrownBy(() -> RecordedDayStream.readMeta(dir)).hasMessageContaining("형식 버전: 99");
        assertThatThrownBy(() -> RecordedReplaySource.streaming(dir)).hasMessageContaining("형식 버전: 99");
    }

    @Test
    void 줄_단위_리플레이_소스는_읽은_녹화일로_만든_소스와_같은_이벤트와_요약을_낸다() {
        Path dir = record(300, 200);
        RecordedReplaySource loaded = new RecordedReplaySource(RecordedDay.open(dir));
        RecordedReplaySource streamed = RecordedReplaySource.streaming(dir);

        assertThat(streamed.sourceId()).isEqualTo(loaded.sourceId()).isEqualTo("REPLAY-KRX-2026-10-08");
        assertThat(streamed.events(Set.of(HYNIX))).isEqualTo(loaded.events(Set.of(HYNIX))).hasSize(101);

        List<MarketEvent> a = new ArrayList<>();
        List<MarketEvent> b = new ArrayList<>();
        loaded.subscribe(Set.of(SAMSUNG, HYNIX), a::add);
        streamed.subscribe(Set.of(SAMSUNG, HYNIX), b::add);
        RecordedDayStream.Summary sa = loaded.replay();
        RecordedDayStream.Summary sb = streamed.replay();
        assertThat(b).isEqualTo(a).hasSize(303);
        assertThat(sb).isEqualTo(sa);

        b.clear();
        assertThat(streamed.replay()).isEqualTo(sa);
        assertThat(b).as("두 번째 리플레이도 같다").isEqualTo(a);
    }

    @Test
    void 줄_단위_리플레이_소스는_부를_때마다_파일을_다시_읽어_바뀐_녹화를_요약으로_드러낸다() throws IOException {
        Path dir = record(30, 0);
        Files.delete(dir.resolve(RecordingLayout.META_FILE)); // 정상 종료 표시 없음: 해시 대조 없이 읽는다
        RecordedReplaySource streamed = RecordedReplaySource.streaming(dir);
        RecordedDayStream.Summary before = streamed.replay();

        dropLastLine(dir.resolve(RecordingLayout.EVENTS_FILE));
        RecordedDayStream.Summary after = streamed.replay();

        assertThat(after.eventCount()).isEqualTo(before.eventCount() - 1);
        assertThat(after).isNotEqualTo(before);
    }

    /** 녹화 파일의 마지막 줄을 지운다(쓰이는 중인 녹화가 읽는 사이 바뀐 경우를 흉내). */
    static void dropLastLine(Path events) throws IOException {
        String text;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(events))) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String body = text.substring(0, text.length() - 1);
        String kept = body.substring(0, body.lastIndexOf('\n') + 1);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(events))) {
            out.write(kept.getBytes(StandardCharsets.UTF_8));
        }
    }
}
