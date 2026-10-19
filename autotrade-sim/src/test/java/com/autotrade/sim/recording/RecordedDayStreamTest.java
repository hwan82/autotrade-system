package com.autotrade.sim.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.recording.RecordedDay;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.sim.testing.FixtureEvents;
import com.autotrade.sim.testing.FixtureRecordings;
import com.autotrade.sim.testing.TestKrxRules;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 줄을 쌓지 않고 읽는 {@link RecordedDayStream}(D-115)이 {@link RecordedDay#open}(marketdata)과 같은 줄을 같은 순서로 넘기고, 요약
 * 값(메타·건수·공백 수·마지막 순번·잘림·표시 없는 재기동 경계·마지막 수신 시각)과 거부 규칙이 같은지 본다. 두 쪽은 같은 읽기 규칙을
 * 따로 가지고 있으므로 녹화 형식의 경우마다 대조한다.
 */
class RecordedDayStreamTest {

    private static final ZoneId ZONE = TestKrxRules.INSTANCE.zone();
    private static final List<MarketEvent> EVENTS = FixtureEvents.load("/fixtures/krx-golden.events", Market.KRX, ZONE);
    private static final LocalDate D1 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 4);
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Instant T = Instant.parse("2026-03-03T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void 정상_종료_녹화와_재기동_녹화는_같은_줄과_요약을_낸다() {
        FixtureRecordings.record(root, Market.KRX, ZONE, EVENTS);
        agrees(new RecordingLayout(root).dayDir(Market.KRX, D1));
        agrees(new RecordingLayout(root).dayDir(Market.KRX, D2));

        Path restart = root.resolve("restart");
        List<MarketEvent> day1 = EVENTS.subList(0, 14);
        FixtureRecordings.record(restart, Market.KRX, ZONE, day1.subList(0, 13));
        FixtureRecordings.record(restart, Market.KRX, ZONE, day1.subList(13, 14));
        Path dir = new RecordingLayout(restart).dayDir(Market.KRX, D1);
        assertThat(RecordedDay.open(dir).meta().orElseThrow().segments()).hasSize(2);
        agrees(dir);
    }

    @Test
    void 공백_표시_없는_이전_녹화의_구간_경계를_같게_알린다() throws IOException {
        Path dir = day("legacy");
        writeEvents(dir, line(1, T), line(2, T.plusSeconds(4440)));
        writeMeta(dir, "[{\"startedAt\":\"" + T + "\",\"endedAt\":\"" + T + "\",\"firstSeq\":1},"
                + "{\"startedAt\":\"" + T.plusSeconds(4440) + "\",\"endedAt\":\"\",\"firstSeq\":2}]", false, 2);
        assertThat(RecordedDay.open(dir).unmarkedRestarts()).hasSize(1);
        agrees(dir);
    }

    @Test
    void 비정상_종료로_잘린_마지막_줄과_깨진_gzip_머리를_같게_다룬다() throws IOException {
        Path cut = day("cut");
        String ok2 = line(2, T);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(cut.resolve(RecordingLayout.EVENTS_FILE)), true)) {
            out.write((line(1, T) + "\n" + ok2 + "\n" + ok2.substring(0, 20)).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        assertThat(RecordedDay.open(cut).truncated()).isTrue();
        agrees(cut);

        byte[][] broken = {new byte[0], {0x1f}, {0x1f, (byte) 0x8b, 8, 0}};
        for (int i = 0; i < broken.length; i++) {
            Path dir = day("head-" + i);
            Files.write(dir.resolve(RecordingLayout.EVENTS_FILE), broken[i]);
            agrees(dir);
        }
    }

    @Test
    void 순번이_끊기거나_정상_종료_녹화의_해시가_다르면_둘_다_거부한다() throws IOException {
        Path gap = day("seq-gap");
        writeEvents(gap, line(1, T), line(3, T));
        assertThatThrownBy(() -> RecordedDay.open(gap)).hasMessageContaining("순번이 끊겼습니다");
        assertThatThrownBy(() -> RecordedDayStream.read(gap, l -> { })).hasMessageContaining("순번이 끊겼습니다");

        FixtureRecordings.record(root, Market.KRX, ZONE, EVENTS);
        Path dir = new RecordingLayout(root).dayDir(Market.KRX, D1);
        Files.write(dir.resolve(RecordingLayout.EVENTS_FILE), new byte[] {0x1f});
        assertThatThrownBy(() -> RecordedDay.open(dir)).hasMessageContaining("SHA-256");
        List<RecordingCodec.Line> seen = new ArrayList<>();
        assertThatThrownBy(() -> RecordedDayStream.read(dir, seen::add)).hasMessageContaining("SHA-256");
        assertThat(seen).as("해시가 다르면 줄을 넘기기 전에 거부").isEmpty();
    }

    @Test
    void 받는_쪽_예외는_그대로_올라가고_그_뒤_줄은_넘기지_않는다() {
        FixtureRecordings.record(root, Market.KRX, ZONE, EVENTS);
        List<Long> seen = new ArrayList<>();
        assertThatThrownBy(() -> RecordedDayStream.read(new RecordingLayout(root).dayDir(Market.KRX, D1), l -> {
            seen.add(l.seq());
            if (l.seq() == 3) {
                throw new IllegalArgumentException("받는 쪽 멈춤");
            }
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("받는 쪽 멈춤");
        assertThat(seen).containsExactly(1L, 2L, 3L);
    }

    private static void agrees(Path dir) {
        RecordedDay day = RecordedDay.open(dir);
        List<RecordingCodec.Line> lines = new ArrayList<>();
        RecordedDayStream.Summary s = RecordedDayStream.read(dir, lines::add);
        assertThat(lines).isEqualTo(day.lines());
        assertThat(s.meta()).isEqualTo(day.meta());
        assertThat(s.truncated()).isEqualTo(day.truncated());
        assertThat(s.eventCount()).isEqualTo(day.eventCount());
        assertThat(s.gapCount()).isEqualTo(day.gapCount());
        assertThat(s.lastSeq()).isEqualTo(day.lastSeq());
        assertThat(s.unmarkedRestarts()).isEqualTo(day.unmarkedRestarts());
        assertThat(s.lastReceivedAt()).isEqualTo(day.lastReceivedAt());
    }

    private Path day(String name) throws IOException {
        Path dir = new RecordingLayout(root.resolve(name)).dayDir(Market.KRX, D1);
        Files.createDirectories(dir);
        return dir;
    }

    private static String line(long seq, Instant t) {
        return new RecordingCodec().encode(seq, "K", new TradeTick(SAMSUNG, seq, t, t, new BigDecimal("71500"), 1));
    }

    private static void writeEvents(Path dir, String... lines) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve(RecordingLayout.EVENTS_FILE)))) {
            out.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeMeta(Path dir, String segments, boolean complete, long count) throws IOException {
        String json = "{\"formatVersion\":" + RecordingCodec.FORMAT_VERSION + ",\"market\":\"KRX\",\"date\":\"" + D1
                + "\",\"zone\":\"Asia/Seoul\",\"sources\":{\"K\":\"KRX_REALTIME\"},\"license\":\"\",\"segments\":" + segments
                + ",\"lastSeq\":" + count + ",\"eventCount\":" + count + ",\"gapCount\":0,\"recorderDropped\":0,\"complete\":"
                + complete + ",\"eventsSha256\":\"\"}";
        Files.writeString(dir.resolve(RecordingLayout.META_FILE), json, StandardCharsets.UTF_8);
    }
}
