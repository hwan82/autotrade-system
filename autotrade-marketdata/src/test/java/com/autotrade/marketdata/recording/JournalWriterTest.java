package com.autotrade.marketdata.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.math.BigDecimal;
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
        first.flush(); // 비정상 종료: close 없이 버린다

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
