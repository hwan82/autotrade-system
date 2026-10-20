package com.autotrade.marketdata.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 같은 날 재기동이 앞 녹화를 줄 단위로 읽으며 다시 쓴다(D-119, PR #179 risk-ops N2). 힙이 그날 녹화 크기와 상관없고,
 * 다시 쓴 줄은 원본 줄과 바이트가 같으며, 읽다가 실패하면 원본·보관본을 건드리지 않고 tmp를 남기지 않는다.
 */
class DayWriterRestartStreamTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    private static final Instant OPEN = Instant.parse("2026-10-21T00:00:00Z"); // 09:00 KST
    private static final String[] CODES = {"005930", "000660", "035420", "005380", "000270", "068270", "105560", "055550",
        "035720", "051910"};
    /** 고치기 전 코드(하루를 통째로 올림)는 이 줄 수를 이 힙에서 다시 열지 못했다(약 1.5KB/줄, 개발 쪽 직접 실행). */
    private static final int HEAVY_LINES = 200_000;
    private static final String SMALL_HEAP = "-Xmx64m";

    @TempDir
    Path root;

    private Path dayDir() {
        return new RecordingLayout(root).dayDir(Market.KRX, DAY);
    }

    private DayWriter open(Instant at) {
        return new DayWriter(dayDir(), Market.KRX, DAY, SEOUL, Map.of("KIWOOM-WS", "KRX_REALTIME"), "", at);
    }

    /** 10종목 10단계 호가 90%·체결 10%(6단계 녹화와 같은 모양). */
    private static MarketEvent event(long i) {
        Symbol s = Symbol.krx(CODES[(int) (i % CODES.length)]);
        Instant t = OPEN.plusMillis(i * 11);
        long base = 50_000 + (i % CODES.length) * 10_000;
        if (i % 10 == 0) {
            return new TradeTick(s, i + 1, t, t.plusMillis(3), BigDecimal.valueOf(base + (i % 20) * 100), 1 + i % 500);
        }
        List<PriceLevel> asks = new ArrayList<>(10);
        List<PriceLevel> bids = new ArrayList<>(10);
        for (int k = 0; k < 10; k++) {
            asks.add(new PriceLevel(BigDecimal.valueOf(base + (k + 1) * 100L), 100 + (i * 7 + k) % 50_000));
            bids.add(new PriceLevel(BigDecimal.valueOf(base - k * 100L), 100 + (i * 13 + k) % 50_000));
        }
        return new OrderBookSnapshot(s, i + 1, t, t.plusMillis(3), asks, bids);
    }

    /** {@code n}줄을 쓰고 flush까지 한 뒤 닫지 못하고 죽은 녹화를 만든다(RecordingReplayTest와 같은 방식). */
    private Path crashedDay(int n) throws IOException {
        DayWriter w = open(OPEN);
        for (long i = 0; i < n; i++) {
            w.write("KIWOOM-WS", event(i));
        }
        w.flush();
        Path dir = dayDir();
        byte[] events = Files.readAllBytes(dir.resolve(RecordingLayout.EVENTS_FILE));
        byte[] meta = Files.readAllBytes(dir.resolve(RecordingLayout.META_FILE));
        w.close();
        Files.write(dir.resolve(RecordingLayout.EVENTS_FILE), events);
        Files.write(dir.resolve(RecordingLayout.META_FILE), meta);
        return dir;
    }

    private static String gunzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 정상_종료_녹화를_다시_쓴_줄은_원본_줄과_바이트가_같다() throws IOException {
        // 소스 순번을 녹화 순번과 다르게 둔다(소스 둘, 첫 줄 KIS-WS 5000·둘째 줄 KIWOOM-WS 1001부터). 같으면 소스 순번 복원이 빠져도 바이트가 같다(PR #187 QA Q1)
        DayWriter first = open(OPEN);
        for (long i = 0; i < 1_000; i++) {
            boolean kis = i % 3 == 0;
            first.write(kis ? "KIS-WS" : "KIWOOM-WS", EventSequences.withSequence(event(i), (kis ? 5_000 : 1_000) + i));
        }
        first.close(OPEN.plusSeconds(30));
        Path events = dayDir().resolve(RecordingLayout.EVENTS_FILE);
        String before = gunzip(events);

        DayWriter second = open(OPEN.plusSeconds(60));
        assertThat(second.lastSeq()).isEqualTo(1_000);
        second.close(OPEN.plusSeconds(61)); // 새 줄 없이 닫으면 뒤에 붙은 빈 gzip 멤버만 늘어난다
        assertThat(gunzip(events)).isEqualTo(before);
        assertThat(dayDir().resolve(RecordingLayout.EVENTS_FILE + ".tmp")).doesNotExist();
        List<RecordingCodec.Line> lines = new ArrayList<>();
        RecordedDayStream.read(dayDir(), lines::add);
        assertThat(lines.get(0).sourceId()).isEqualTo("KIS-WS");
        assertThat(lines.get(0).sourceSequence()).isEqualTo(5_000);
        assertThat(lines.get(1).sourceId()).isEqualTo("KIWOOM-WS");
        assertThat(lines.get(1).sourceSequence()).isEqualTo(1_001);
    }

    @Test
    void 앞_날_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다() throws IOException {
        // carryRestartFrom 공백 시작이 앞 날 마지막 구간 끝을 반영하는지 고정한다(PR #187 QA Q2)
        LocalDate prevDay = DAY.minusDays(1);
        Path previous = new RecordingLayout(root).dayDir(Market.KRX, prevDay);
        Instant prevOpen = OPEN.minus(Duration.ofDays(1));
        DayWriter prev = new DayWriter(previous, Market.KRX, prevDay, SEOUL, Map.of("KIWOOM-WS", "KRX_REALTIME"), "", prevOpen);
        Instant lastRx = prevOpen.plusSeconds(10);
        prev.write("KIWOOM-WS", new TradeTick(Symbol.krx("005930"), 1, prevOpen, prevOpen, BigDecimal.valueOf(70_000), 1));
        prev.write("KIWOOM-WS", new TradeTick(Symbol.krx("000660"), 1, lastRx, lastRx, BigDecimal.valueOf(120_000), 1));
        Instant ended = prevOpen.plus(Duration.ofHours(7)); // 마지막 수신(000660 체결, lastRx)보다 한참 늦은 정상 종료
        prev.close(ended);
        assertThat(ended).isAfter(lastRx);

        DayWriter w = open(OPEN);
        w.carryRestartFrom(previous);
        Instant resumed = OPEN.plusSeconds(5);
        List<DataGap> gaps = w.write("KIWOOM-WS", new TradeTick(Symbol.krx("005930"), 1, resumed, resumed,
                BigDecimal.valueOf(70_000), 1));
        assertThat(gaps).extracting(DataGap::symbol).containsExactly(Symbol.krx("000660"), Symbol.krx("005930"));
        assertThat(gaps).allSatisfy(g -> {
            assertThat(g.gapStart()).isEqualTo(ended);
            assertThat(g.gapEnd()).isEqualTo(resumed);
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.RECORDER_RESTART);
        });
        w.close(resumed.plusSeconds(1));
    }

    @Test
    void 같은_날_앞_구간_정상_종료_시각이_마지막_수신보다_늦으면_그_시각부터_재기동_공백을_남긴다() throws IOException {
        // 같은 날 재기동 공백 시작이 앞 구간 끝을 반영하는지 고정한다(PR #192 QA Q1, 변이 P15). 날짜를 넘긴 쪽 짝은 위 시험
        DayWriter first = open(OPEN);
        Instant lastRx = OPEN.plusSeconds(10);
        first.write("KIWOOM-WS", new TradeTick(Symbol.krx("005930"), 1, OPEN, OPEN, BigDecimal.valueOf(70_000), 1));
        first.write("KIWOOM-WS", new TradeTick(Symbol.krx("000660"), 2, lastRx, lastRx, BigDecimal.valueOf(120_000), 1));
        Instant ended = OPEN.plusSeconds(600); // 마지막 수신보다 늦고 재개보다 이른 정상 종료
        first.close(ended);

        DayWriter second = open(OPEN.plusSeconds(900));
        Instant resumed = OPEN.plusSeconds(905);
        List<DataGap> gaps = second.write("KIWOOM-WS", new TradeTick(Symbol.krx("005930"), 3, resumed, resumed,
                BigDecimal.valueOf(70_100), 1));
        assertThat(gaps).extracting(DataGap::symbol).containsExactly(Symbol.krx("000660"), Symbol.krx("005930"));
        assertThat(gaps).allSatisfy(g -> {
            assertThat(g.gapStart()).isEqualTo(ended);
            assertThat(g.gapEnd()).isEqualTo(resumed);
            assertThat(g.reason()).isEqualTo(DataGap.GapReason.RECORDER_RESTART);
        });
        second.close(resumed.plusSeconds(1));
        // 녹화 파일에 남은 공백 줄도 같은 시작이다
        List<RecordingCodec.Line> lines = new ArrayList<>();
        RecordedDayStream.read(dayDir(), lines::add);
        assertThat(((DataGap) lines.get(2).event()).gapStart()).isEqualTo(ended);
    }

    @Test
    void 다시_쓰기_중_쓰기가_실패하면_폴더를_밝힌_문구로_멈추고_원본을_그대로_둔다() throws IOException {
        // 다시 쓰기 실패 문구(PR #187 architect 2)를 고정한다(PR #192 QA Q2, 변이 P13). 읽기 실패 시험은 위에 있다
        Path dir = crashedDay(2_000);
        Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
        byte[] original = Files.readAllBytes(events);
        Path tmp = dir.resolve(RecordingLayout.EVENTS_FILE + ".tmp");
        // gzip 머리(10바이트)는 받고 그 뒤 첫 쓰기에서 실패한다: 줄을 쓰는 중에 실패가 난다
        DayWriter.TmpOpener failing = t -> new FilterOutputStream(Files.newOutputStream(t)) {
            private long written;

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (written + len > 10) {
                    throw new IOException("디스크 가득 참(시험)");
                }
                written += len;
                out.write(b, off, len);
            }
        };

        assertThatThrownBy(() -> new DayWriter(dir, Market.KRX, DAY, SEOUL, Map.of("KIWOOM-WS", "KRX_REALTIME"), "",
                OPEN.plusSeconds(60), failing))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("녹화 재기동 다시 쓰기 실패: " + dir)
                .hasRootCauseMessage("디스크 가득 참(시험)");
        assertThat(events).hasBinaryContent(original);
        assertThat(tmp).doesNotExist();
        assertThat(dir.resolve(DayWriter.restartBackupName(2))).doesNotExist();
    }

    @Test
    void 앞_녹화를_읽다_실패하면_원본을_그대로_두고_tmp와_보관본을_남기지_않는다() throws IOException {
        // 비정상 종료 녹화에서 3번째 줄 순번이 끊김: 앞 두 줄은 이미 tmp에 쓴 뒤에 실패한다
        Path dir = dayDir();
        Files.createDirectories(dir);
        RecordingCodec codec = new RecordingCodec();
        StringBuilder text = new StringBuilder();
        for (long seq : new long[] {1, 2, 4, 5}) {
            text.append(codec.encode(seq, "KIWOOM-WS", event(seq))).append('\n');
        }
        Path events = dir.resolve(RecordingLayout.EVENTS_FILE);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(events))) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        byte[] original = Files.readAllBytes(events);

        assertThatThrownBy(() -> open(OPEN.plusSeconds(60))).hasMessageContaining("순번");
        assertThat(events).hasBinaryContent(original);
        assertThat(dir.resolve(RecordingLayout.EVENTS_FILE + ".tmp")).doesNotExist();
        assertThat(dir.resolve(DayWriter.restartBackupName(1))).doesNotExist();
    }

    @Test
    void 앞_날을_읽다_실패하면_재기동_공백을_넘겨받지_않는다() throws IOException {
        Path previous = new RecordingLayout(root).dayDir(Market.KRX, DAY.minusDays(1));
        Files.createDirectories(previous);
        RecordingCodec codec = new RecordingCodec();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(previous.resolve(RecordingLayout.EVENTS_FILE)))) {
            out.write((codec.encode(1, "KIWOOM-WS", event(0)) + "\n" + codec.encode(3, "KIWOOM-WS", event(1)) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        DayWriter w = open(OPEN);
        assertThatThrownBy(() -> w.carryRestartFrom(previous)).hasMessageContaining("순번");
        assertThat(w.write("KIWOOM-WS", event(0))).isEmpty();
        w.close(OPEN.plusSeconds(1));
    }

    @Test
    void 큰_녹화도_작은_힙에서_재기동한다() throws Exception {
        Path dir = crashedDay(HEAVY_LINES);
        Path argFile = root.resolve("probe.args");
        // Windows 명령 줄 길이 한도를 피하려고 클래스패스는 인자 파일로 넘긴다
        Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // 출력은 파일로 받는다: 자식이 멈춰도 시간 제한이 듣게(PR #187 risk-ops N1)
        Path log = root.resolve("probe.log");
        Process p = new ProcessBuilder(java.toString(), SMALL_HEAP, "@" + argFile, Probe.class.getName(), dir.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean exited = p.waitFor(5, TimeUnit.MINUTES);
        if (!exited) {
            p.destroyForcibly().waitFor();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(exited).as(output).isTrue();
        assertThat(p.exitValue()).as(output).isZero();
        assertThat(output).contains("lastSeq=" + HEAVY_LINES);

        RecordedDayStream.Summary after = RecordedDayStream.read(dir, l -> { });
        assertThat(after.truncated()).isFalse();
        assertThat(after.lastSeq()).isEqualTo(HEAVY_LINES + CODES.length + 1L); // 재기동 공백 10 + 새 줄 1
        assertThat(after.unmarkedRestarts()).isEmpty();
        assertThat(dir.resolve(DayWriter.restartBackupName(2))).exists();
    }

    /** 작은 힙으로 띄운 별도 JVM에서 재기동하고 한 줄 쓴 뒤 정상 종료한다. */
    static final class Probe {
        public static void main(String[] args) throws IOException {
            Path dir = Path.of(args[0]);
            Instant at = OPEN.plusSeconds(3_600);
            DayWriter w = new DayWriter(dir, Market.KRX, DAY, SEOUL, Map.of("KIWOOM-WS", "KRX_REALTIME"), "", at);
            System.out.println("lastSeq=" + w.lastSeq());
            w.write("KIWOOM-WS", new TradeTick(Symbol.krx("005930"), 1, at, at, BigDecimal.valueOf(70_000), 1));
            w.close(at.plusSeconds(1));
        }
    }
}
