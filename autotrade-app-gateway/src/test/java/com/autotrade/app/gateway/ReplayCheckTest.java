package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import com.autotrade.marketdata.recording.RecordingLayout;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayCheckTest {

    private static final Instant OPEN = Instant.parse("2026-10-08T00:00:00Z"); // 09:00 KST
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final Symbol HYNIX = Symbol.krx("000660");

    @TempDir
    Path root;

    private Path recordDay() {
        MarketEventRecorder rec = new MarketEventRecorder(new RecordingLayout(root), Market.KRX, ZoneId.of("Asia/Seoul"),
                Clock.fixed(OPEN, ZoneOffset.UTC), Map.of("KIWOOM-WS", "KRX_REALTIME"), "본인 연구용, 외부 공유 금지",
                1_000, Duration.ofMillis(50));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 1, OPEN, OPEN.plusMillis(2), new BigDecimal("71000"), 10));
        rec.offer("KIWOOM-WS", new TradeTick(HYNIX, 2, OPEN.plusSeconds(1), OPEN.plusSeconds(1), new BigDecimal("120500"), 3));
        rec.offer("KIWOOM-WS", new OrderBookSnapshot(SAMSUNG, 3, OPEN.plusSeconds(2), OPEN.plusSeconds(2),
                List.of(new PriceLevel(new BigDecimal("71100"), 5)), List.of(new PriceLevel(new BigDecimal("71000"), 7))));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 4, OPEN.plusSeconds(3), OPEN.plusSeconds(3), new BigDecimal("71300"), 4));
        rec.offer("KIWOOM-WS", new DataGap(SAMSUNG, 5, OPEN.plusSeconds(9), OPEN.plusSeconds(9), OPEN.plusSeconds(4),
                OPEN.plusSeconds(9), DataGap.GapReason.DISCONNECTED));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 6, OPEN.plusSeconds(10), OPEN.plusSeconds(10), new BigDecimal("70900"), 6));
        rec.offer("KIWOOM-WS", new TradeTick(SAMSUNG, 7, OPEN.plusSeconds(6 * 3600 + 1800), OPEN.plusSeconds(6 * 3600 + 1800),
                new BigDecimal("71200"), 2));
        rec.close();
        return new RecordingLayout(root).dayDir(Market.KRX, LocalDate.of(2026, 10, 8));
    }

    @Test
    void 같은_녹화일을_두_번_읽어_리플레이하면_해시가_같고_종목별_체결_요약을_현지_시각으로_낸다() {
        ReplayCheck.Report r = ReplayCheck.check(recordDay());

        assertThat(r.identical()).isTrue();
        assertThat(r.firstDigest()).hasSize(64).isEqualTo(r.secondDigest());
        assertThat(r.eventCount()).isEqualTo(7);
        assertThat(r.gapCount()).isEqualTo(1);
        String md = r.toMarkdown();
        assertThat(md).contains("판정: **동일**")
                .contains("| 005930 | 4 | 71000 | 71300 | 70900 | 71200 | 22 | 09:00 | 15:30 | 1 | 1 |")
                .contains("| 000660 | 1 | 120500 | 120500 | 120500 | 120500 | 3 | 09:00:01 | 09:00:01 | 0 | 0 |")
                .contains("정상 종료 예, 건수 7");
    }

    @Test
    void 보고서_파일을_쓰고_동일하면_0을_돌려준다() throws Exception {
        Path day = recordDay();
        Path out = root.resolve("report.md");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = ReplayCheck.run(new String[] {day.toString(), out.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(code).isZero();
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).isEqualTo(stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void 녹화가_없거나_손상되면_2를_돌려준다() throws Exception {
        Path day = recordDay();
        Files.write(day.resolve(RecordingLayout.EVENTS_FILE), new byte[] {1, 2, 3});
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        assertThat(ReplayCheck.run(new String[] {day.toString()}, sink, new PrintStream(err, true, StandardCharsets.UTF_8))).isEqualTo(2);
        assertThat(ReplayCheck.run(new String[] {root.resolve("KRX/2026-01-02").toString()}, sink, sink)).isEqualTo(2);
        assertThat(ReplayCheck.run(new String[0], sink, sink)).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("녹화 확인 실패");
    }
}
