package com.autotrade.marketdata.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실시간 모의 입력 기록(ADR-010 2.3, D-076): 돌려준 줄이 녹화 파일과 같고, 이어 쓰면 재기동 공백을 돌려준다. */
class JournalWriterTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant OPEN = Instant.parse("2026-10-14T00:00:00Z"); // 09:00 KST
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");

    @TempDir
    Path root;

    private JournalWriter writer() {
        return new JournalWriter(new RecordingLayout(root), Market.KRX, SEOUL, Map.of("KIWOOM-WS", "KRX_REALTIME"),
                "본인 연구용, 외부 공유 금지");
    }

    /**
     * flush까지 한 뒤 닫지 못하고 죽은 기록기를 만든다. flush 직후 그날 파일을 떠 두고, 닫은 뒤 그 사본으로 되돌린다.
     * 닫지 않은 채 두면 같은 JVM에 열린 핸들이 남아 Windows에서 재기동의 파일 바꾸기가 거부된다(실제 비정상 종료는 프로세스가
     * 죽어 핸들이 없다). RecordingReplayTest#crash와 같은 방식
     */
    private void crash(JournalWriter w) {
        w.flush();
        Path dir = new RecordingLayout(root).dayDir(Market.KRX, w.currentDate());
        try {
            byte[] events = Files.readAllBytes(dir.resolve(RecordingLayout.EVENTS_FILE));
            byte[] meta = Files.readAllBytes(dir.resolve(RecordingLayout.META_FILE));
            w.close();
            Files.write(dir.resolve(RecordingLayout.EVENTS_FILE), events);
            Files.write(dir.resolve(RecordingLayout.META_FILE), meta);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TradeTick tick(Symbol s, long srcSeq, Instant t) {
        return new TradeTick(s, srcSeq, t, t, new BigDecimal("70000"), 1);
    }

    @Test
    void returnedLinesMatchTheRecordedFile() {
        JournalWriter w = writer();
        List<MarketEvent> a = w.append("KIWOOM-WS", tick(SAMSUNG, 41, OPEN), OPEN);
        List<MarketEvent> b = w.append("KIWOOM-WS", tick(HYNIX, 42, OPEN.plusSeconds(1)), OPEN.plusSeconds(1));
        w.close(OPEN.plusSeconds(2));

        assertThat(a).singleElement().satisfies(e -> assertThat(e.sequence()).isEqualTo(1));
        assertThat(b).singleElement().satisfies(e -> assertThat(e.sequence()).isEqualTo(2));
        assertThat(w.currentDate()).isEqualTo(LocalDate.of(2026, 10, 14));

        RecordedDay day = RecordedDay.open(new RecordingLayout(root).dayDir(Market.KRX, LocalDate.of(2026, 10, 14)));
        assertThat(day.lines()).extracting(RecordingCodec.Line::seq).containsExactly(1L, 2L);
        assertThat(day.lines()).extracting(RecordingCodec.Line::sourceSequence).containsExactly(41L, 42L);
        assertThat(day.lines()).extracting(RecordingCodec.Line::event).containsExactly(a.get(0), b.get(0));
        assertThat(day.truncated()).isFalse();
    }

    @Test
    void reopeningTheSameDayReturnsRestartGapsBeforeTheEvent() {
        JournalWriter first = writer();
        first.append("KIWOOM-WS", tick(SAMSUNG, 1, OPEN), OPEN);
        first.append("KIWOOM-WS", tick(HYNIX, 2, OPEN.plusSeconds(1)), OPEN.plusSeconds(1));
        crash(first); // 비정상 종료: close 없이 버린 것과 같은 파일

        Instant resumed = OPEN.plusSeconds(60);
        JournalWriter second = writer();
        List<MarketEvent> out = second.append("KIWOOM-WS", tick(SAMSUNG, 3, resumed), resumed);
        second.close(resumed.plusSeconds(1));

        assertThat(out).hasSize(3);
        assertThat(out.subList(0, 2)).allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(DataGap.class,
                g -> assertThat(g.reason()).isEqualTo(DataGap.GapReason.RECORDER_RESTART)));
        assertThat(out).extracting(MarketEvent::sequence).containsExactly(3L, 4L, 5L);

        RecordedDay day = RecordedDay.open(new RecordingLayout(root).dayDir(Market.KRX, LocalDate.of(2026, 10, 14)));
        assertThat(day.lines()).extracting(RecordingCodec.Line::seq).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(day.lines().subList(2, 5)).extracting(RecordingCodec.Line::event).containsExactlyElementsOf(out);
    }

    @Test
    void resumingOnALaterDayReturnsRestartGapsFromTheLastDayBeforeTheEvent() {
        // 1일차 장중에 멈췄다가 2일차에 이어 받으면 1일차 나머지가 빠진 것을 새 날 첫 줄 앞 공백으로 남긴다(QA PR #91 R1)
        JournalWriter first = writer();
        first.append("KIWOOM-WS", tick(SAMSUNG, 1, OPEN), OPEN);
        Instant lastAlive = OPEN.plusSeconds(1);
        first.append("KIWOOM-WS", tick(HYNIX, 2, lastAlive), lastAlive);
        crash(first); // 비정상 종료

        Instant nextDay = OPEN.plusSeconds(24 * 3600);
        JournalWriter second = writer();
        second.resumeAfter(LocalDate.of(2026, 10, 14));
        List<MarketEvent> out = second.append("KIWOOM-WS", tick(SAMSUNG, 3, nextDay), nextDay);
        List<MarketEvent> later = second.append("KIWOOM-WS", tick(HYNIX, 4, nextDay.plusSeconds(1)), nextDay);
        second.close(nextDay.plusSeconds(2));

        assertThat(out).hasSize(3);
        assertThat(out.subList(0, 2)).extracting(MarketEvent::symbol).containsExactly(HYNIX, SAMSUNG);
        assertThat(out.subList(0, 2)).allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(DataGap.class, g -> {
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.RECORDER_RESTART);
            assertThat(g.gapStart()).isEqualTo(lastAlive);
            assertThat(g.gapEnd()).isEqualTo(nextDay);
        }));
        assertThat(out).extracting(MarketEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(later).singleElement().satisfies(e -> assertThat(e.sequence()).isEqualTo(4));

        RecordingLayout layout = new RecordingLayout(root);
        assertThat(RecordedDay.open(layout.dayDir(Market.KRX, LocalDate.of(2026, 10, 15))).lines())
                .extracting(RecordingCodec.Line::seq).containsExactly(1L, 2L, 3L, 4L);
        // 앞 날 파일은 다시 열지 않는다
        assertThat(RecordedDay.open(layout.dayDir(Market.KRX, LocalDate.of(2026, 10, 14))).lines()).hasSize(2);
    }

    @Test
    void resumingOnTheSameDayKeepsTheSameDayRestartGapsOnly() {
        JournalWriter first = writer();
        first.append("KIWOOM-WS", tick(SAMSUNG, 1, OPEN), OPEN);
        crash(first);

        Instant resumed = OPEN.plusSeconds(60);
        JournalWriter second = writer();
        second.resumeAfter(LocalDate.of(2026, 10, 14));
        List<MarketEvent> out = second.append("KIWOOM-WS", tick(SAMSUNG, 2, resumed), resumed);
        second.close(resumed);

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(DataGap.class);
        assertThatThrownBy(() -> second.resumeAfter(LocalDate.of(2026, 10, 14))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void splitsDaysByExchangeLocalDateOfReceipt() {
        JournalWriter w = writer();
        w.append("KIWOOM-WS", tick(SAMSUNG, 1, OPEN), OPEN);
        Instant nextDay = OPEN.plusSeconds(24 * 3600);
        List<MarketEvent> out = w.append("KIWOOM-WS", tick(SAMSUNG, 2, nextDay), nextDay);
        w.close(nextDay);

        assertThat(out).singleElement().satisfies(e -> assertThat(e.sequence()).isEqualTo(1));
        assertThat(w.currentDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        RecordingLayout layout = new RecordingLayout(root);
        assertThat(RecordedDay.open(layout.dayDir(Market.KRX, LocalDate.of(2026, 10, 14))).lines()).hasSize(1);
        assertThat(RecordedDay.open(layout.dayDir(Market.KRX, LocalDate.of(2026, 10, 15))).lines()).hasSize(1);
    }

    @Test
    void refusesOtherMarketsAndWritesAfterClose() {
        JournalWriter w = writer();
        assertThatThrownBy(() -> w.append("KIWOOM-WS", tick(Symbol.us("AAPL"), 1, OPEN), OPEN))
                .isInstanceOf(IllegalArgumentException.class);
        w.close(OPEN);
        assertThatThrownBy(() -> w.append("KIWOOM-WS", tick(SAMSUNG, 1, OPEN), OPEN))
                .isInstanceOf(IllegalStateException.class);
    }
}
