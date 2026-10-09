package com.autotrade.app.gateway;

import com.autotrade.config.ConfigPaths;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.recording.RecordedDay;
import com.autotrade.marketdata.recording.RecordedReplaySource;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 녹화일 확인 도구(2단계 완료 기준 MD-06, QA 2단계 재판정 C1·G-1). 네트워크와 주문을 쓰지 않고 녹화 파일만 읽는다.
 *
 * <ul>
 *   <li>같은 녹화일을 디스크에서 두 번 따로 읽어 리플레이하고, 두 결과(순서와 값)의 SHA-256이 같은지 본다.</li>
 *   <li>메타의 건수·{@code eventsSha256}과 실제 파일이 맞는지는 {@link RecordedDay#open}이 검사한다.</li>
 *   <li>원본 대조용으로 종목별 체결 요약(시가·고가·저가·종가·체결 수량 합, 거래소 현지 시각)을 낸다.
 *       증권사 HTS의 같은 날 일봉과 대조하면 가격 자릿수나 시각 변환 오류를 잡을 수 있다(두 번 리플레이 동일성만으로는 못 잡는다).</li>
 * </ul>
 *
 * <p>사용: {@code ./gradlew :autotrade-app-gateway:replayCheck --args="2026-10-12"} (설정 폴더의 {@code recordings/KRX/<날짜>}),
 * 또는 녹화일 폴더 경로. 두 번째 인자로 보고서 파일 경로를 주면 같은 내용을 Markdown으로 쓴다.
 * 종료 코드 0: 두 리플레이 동일, 1: 다름, 2: 읽기 실패.
 */
public final class ReplayCheck {

    private ReplayCheck() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1 || args.length > 2) {
            err.println("사용법: replayCheck <yyyy-MM-dd | 녹화일 폴더> [보고서 파일]");
            return 2;
        }
        try {
            Report report = check(resolveDay(args[0]));
            String text = report.toMarkdown();
            out.print(text);
            if (args.length == 2) {
                Files.writeString(Path.of(args[1]), text, StandardCharsets.UTF_8);
            }
            return report.identical() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            err.println("녹화 확인 실패: " + e.getMessage());
            return 2;
        }
    }

    private static Path resolveDay(String arg) {
        if (arg.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Path config = ConfigPaths.defaultLocation().runtimeFile().toAbsolutePath().getParent();
            return new RecordingLayout(config.resolve(GatewayMain.RECORDINGS_DIR)).dayDir(Market.KRX, LocalDate.parse(arg));
        }
        return Path.of(arg);
    }

    /** 녹화일 하나를 두 번 리플레이해 비교하고 종목별 요약을 만든다. */
    static Report check(Path dayDir) {
        Run first = replayOnce(dayDir);
        Run second = replayOnce(dayDir);
        RecordedDay day = first.day();
        ZoneId zone = day.meta().map(m -> ZoneId.of(m.zone()))
                .orElseGet(() -> zoneOf(Market.valueOf(dayDir.getParent().getFileName().toString())));
        return new Report(dayDir, day.meta(), day.truncated(), day.eventCount(), day.gapCount(),
                first.digest(), second.digest(), first.events().size(), second.events().size(),
                summarize(first.events(), zone));
    }

    private record Run(RecordedDay day, List<MarketEvent> events, String digest) {}

    private static Run replayOnce(Path dayDir) {
        RecordedDay day = RecordedDay.open(dayDir);
        Set<Symbol> symbols = new TreeSet<>();
        for (RecordingCodec.Line line : day.lines()) {
            symbols.add(line.event().symbol());
        }
        RecordedReplaySource source = new RecordedReplaySource(day);
        List<MarketEvent> events = new ArrayList<>();
        source.subscribe(symbols, events::add);
        source.replay();
        MessageDigest sha = sha256();
        for (MarketEvent e : events) {
            sha.update((e + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Run(day, events, HexFormat.of().formatHex(sha.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    private static ZoneId zoneOf(Market market) {
        return switch (market) {
            case KRX -> ZoneId.of("Asia/Seoul");
            case US -> ZoneId.of("America/New_York");
        };
    }

    private static Map<Symbol, SymbolSummary> summarize(List<MarketEvent> events, ZoneId zone) {
        Map<Symbol, SymbolSummary> out = new TreeMap<>();
        for (MarketEvent e : events) {
            out.computeIfAbsent(e.symbol(), s -> new SymbolSummary(zone)).add(e);
        }
        return out;
    }

    /** 종목 하나의 하루 요약. 체결 순서는 녹화 순번 순서다. */
    static final class SymbolSummary {
        private final ZoneId zone;
        long trades;
        long books;
        long gaps;
        long volume;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        Instant firstTrade;
        Instant lastTrade;

        SymbolSummary(ZoneId zone) {
            this.zone = zone;
        }

        void add(MarketEvent e) {
            switch (e) {
                case TradeTick t -> {
                    trades++;
                    volume += t.quantity();
                    if (open == null) {
                        open = t.price();
                        high = t.price();
                        low = t.price();
                        firstTrade = t.exchangeTime();
                    }
                    high = high.max(t.price());
                    low = low.min(t.price());
                    close = t.price();
                    lastTrade = t.exchangeTime();
                }
                case OrderBookSnapshot b -> books++;
                case DataGap g -> gaps++;
                case InstrumentStatusChanged c -> { }
            }
        }

        private String time(Instant t) {
            return t == null ? "-" : LocalTime.ofInstant(t, zone).truncatedTo(ChronoUnit.SECONDS).toString();
        }

        private static String px(BigDecimal p) {
            return p == null ? "-" : p.toPlainString();
        }

        String row(Symbol s) {
            return "| " + s.code() + " | " + trades + " | " + px(open) + " | " + px(high) + " | " + px(low) + " | "
                    + px(close) + " | " + volume + " | " + time(firstTrade) + " | " + time(lastTrade) + " | " + books
                    + " | " + gaps + " |";
        }
    }

    record Report(Path dir, Optional<RecordingMeta> meta, boolean truncated, long eventCount, long gapCount,
            String firstDigest, String secondDigest, long firstCount, long secondCount,
            Map<Symbol, SymbolSummary> symbols) {

        boolean identical() {
            return firstCount == secondCount && firstDigest.equals(secondDigest);
        }

        String toMarkdown() {
            StringBuilder b = new StringBuilder();
            b.append("# 녹화일 두 번 리플레이 확인\n\n");
            b.append("- 녹화일 폴더: `").append(dir.getParent().getFileName()).append('/').append(dir.getFileName()).append("`\n");
            if (meta.isPresent()) {
                RecordingMeta m = meta.get();
                b.append("- 메타: 정상 종료 ").append(m.complete() ? "예" : "아니오")
                        .append(", 건수 ").append(m.eventCount())
                        .append(", 녹화기 누락 ").append(m.recorderDropped())
                        .append(", eventsSha256 `").append(m.eventsSha256().isEmpty() ? "-" : m.eventsSha256())
                        .append("` (파일과 일치 확인됨)\n");
                b.append("- 출처: ").append(new TreeMap<>(m.sources())).append('\n');
                b.append("- 녹화 구간 수(재기동 포함): ").append(m.segments().size()).append('\n');
            } else {
                b.append("- 메타: 없음(정상 종료되지 않음)\n");
            }
            b.append("- 마지막 줄 잘림: ").append(truncated ? "예" : "아니오").append('\n');
            b.append("- 이벤트 수: ").append(eventCount).append(", 끊김 표시: ").append(gapCount).append('\n');
            b.append("- 리플레이 1회차: ").append(firstCount).append("건, SHA-256 `").append(firstDigest).append("`\n");
            b.append("- 리플레이 2회차: ").append(secondCount).append("건, SHA-256 `").append(secondDigest).append("`\n");
            b.append("- 판정: ").append(identical() ? "**동일**" : "**다름**").append("\n\n");
            b.append("## 종목별 체결 요약 (원본 대조용, 시각은 거래소 현지)\n\n");
            b.append("| 종목 | 체결 수 | 시가 | 고가 | 저가 | 종가 | 체결 수량 합 | 첫 체결 | 마지막 체결 | 호가 수 | 끊김 |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            symbols.forEach((s, sum) -> b.append(sum.row(s)).append('\n'));
            return b.toString();
        }
    }
}
