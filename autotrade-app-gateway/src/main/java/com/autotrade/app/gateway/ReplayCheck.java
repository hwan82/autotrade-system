package com.autotrade.app.gateway;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.calendar.CalendarCoverageException;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.calendar.TradingSession;
import com.autotrade.marketdata.recording.RecordedDayStream;
import com.autotrade.marketdata.recording.RecordedReplaySource;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
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
 *   <li>같은 녹화일을 디스크에서 두 번 따로 읽어 리플레이하고, 두 결과(순서와 값)의 SHA-256이 같은지 본다.
 *       해시는 이벤트 레코드의 문자열 표현으로 만들어 같은 실행 안의 두 회차 비교에만 쓴다.</li>
 *   <li>메타 {@code eventsSha256}과 파일의 일치는 정상 종료된 녹화에서만 {@link RecordedDayStream}이 줄을 넘기기 전에
 *       검사한다(녹화일을 줄 단위로 읽는다, D-117).
 *       정상 종료가 아니거나, 마지막 줄이 잘렸거나, 0건이면 두 회차가 같아도 "불완전"으로 판정한다.</li>
 *   <li>원본 대조용으로 종목별 체결 요약을 낸다. 시가·고가·저가·종가·수량은 정규장(캘린더 구간, 종가 단일가 체결을 위해
 *       마감 뒤 1분까지 포함 {@code [가정]}) 체결만 집계하고, 그 밖의 체결(시간외 등)은 건수와 수량을 따로 적는다.</li>
 * </ul>
 *
 * <p>사용: {@code ./gradlew :autotrade-app-gateway:replayCheck --args="2026-10-12"} (설정 폴더의 {@code recordings/KRX/<날짜>}),
 * 또는 녹화일 폴더 경로. 두 번째 인자로 보고서 파일 경로를 주면 같은 내용을 Markdown으로 쓴다.
 * 표의 종목명은 설정 폴더의 {@code symbols/KRX.csv}({@link SymbolMasterUpdate}로 만듦)에서 읽으며, 없으면 "–"로 둔다.
 * 종목명은 표시용이라 두 회차 비교와 해시에 들어가지 않는다.
 *
 * <p><b>정지 확인(D-085, Q-050)</b>: 실험 실행 명령(D-063)과 같은 관문({@link ReplayCheckGate})을 시작 때, 회차마다, 회차 안에서
 * {@value #CHECK_EVERY}줄마다 묻는다. 감시의 정지 요청 파일이 있거나 장 시간 보호 구간이면 시작하지 않고, 실행 중이면 멈춘다. 멈추면
 * 보고서를 출력하지도 쓰지도 않는다(이전 보고서 파일은 그대로). 장 시간 보호는 실전 엔진을 끈 날에만 {@code -PallowDuringSession}
 * (인자 {@code --allow-during-session}, 값이 없거나 {@code true}만)으로 푼다. 정지 요청 파일은 풀리지 않는다.
 * 종료 코드 0: 동일하고 녹화 완전, 1: 다름, 2: 읽기 실패·입력 오류, 3: 동일하나 녹화 불완전, 4: 정지 확인으로 멈춤(결과 없음).
 */
public final class ReplayCheck {

    /** 종가 단일가 체결 시각이 마감 시각과 같거나 조금 뒤로 찍히는 경우를 정규장으로 본다 {@code [가정]}(Q-015 실측 후 조정). */
    static final Duration CLOSING_PRINT_GRACE = Duration.ofMinutes(1);

    /** 회차 안에서 정지 확인을 묻는 간격(녹화 줄 수). 2026-10-12 실녹화 52만 줄 기준 회차마다 10번 남짓이다. */
    static final int CHECK_EVERY = 50_000;

    /** 정지 요청으로 멈춤. */
    static final int EXIT_STOPPED = 4;

    /** 정지 확인. 멈춰야 하면 이유를 돌려준다. */
    @FunctionalInterface
    interface StopCheck {
        StopCheck NONE = Optional::empty;

        Optional<String> stopReason();
    }

    /** 정지 확인이 멈추라고 해서 점검을 끝냄. 보고서를 쓰지 않는다. */
    static final class StoppedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StoppedException(String reason) {
            super(reason, null, false, false);
        }
    }

    private ReplayCheck() {}

    /**
     * 명령 진입({@link ReplayCheckMain}). 인자에서 {@code --allow-during-session[=true]}를 떼어 정지 확인을 만들고, 시작 전에 묻는다.
     *
     * @param config 설정 폴더(녹화 폴더, 종목명 표, 정지 요청 파일의 기준)
     */
    static int run(String[] args, Clock clock, Path config, PrintStream out, PrintStream err) {
        List<String> rest = new ArrayList<>();
        boolean allow = false;
        for (String a : args) {
            if (List.of("--allow-during-session", "--allow-during-session=", "--allow-during-session=true").contains(a)) {
                allow = true;
            } else if (a.startsWith("--allow-during-session")) {
                err.println("--allow-during-session은 값이 없거나 true여야 합니다(풀지 않으려면 옵션을 빼세요): " + a);
                return 2;
            } else {
                rest.add(a);
            }
        }
        ReplayCheckGate gate = ReplayCheckGate.of(clock, config, allow);
        // 표준 출력은 보고서 본문이라 안내는 표준 오류로 낸다
        err.println("[replayCheck] 정지 요청 파일 확인 위치: " + gate.stopRequest());
        if (allow) {
            err.println("[replayCheck] 장 시간 보호 해제됨(실전 엔진을 끈 날만). 정지 요청 파일은 계속 확인합니다");
        }
        Optional<String> stop = gate.stopReason();
        if (stop.isPresent()) {
            err.println("리플레이 점검을 시작하지 않습니다: " + stop.get());
            return EXIT_STOPPED;
        }
        SymbolNameFiles.Loaded loaded = SymbolNameFiles.load(config);
        loaded.warnings().forEach(err::println);
        return run(rest.toArray(String[]::new), config, gate, loaded.names(), out, err);
    }

    static int run(String[] args, Path config, StopCheck gate, SymbolNames names, PrintStream out, PrintStream err) {
        if (args.length < 1 || args.length > 2) {
            err.println("사용법: replayCheck <yyyy-MM-dd | 녹화일 폴더> [보고서 파일] [--allow-during-session]");
            return 2;
        }
        try {
            Report report = check(resolveDay(args[0], config), names, gate);
            String text = report.toMarkdown();
            out.print(text);
            if (args.length == 2) {
                Files.writeString(Path.of(args[1]), text, StandardCharsets.UTF_8);
            }
            return report.verdict().exitCode;
        } catch (StoppedException e) {
            err.println("리플레이 점검을 멈췄습니다(보고서를 쓰지 않음): " + e.getMessage());
            return EXIT_STOPPED;
        } catch (IOException | RuntimeException e) {
            err.println("녹화 확인 실패: " + e.getMessage());
            return 2;
        }
    }

    private static Path resolveDay(String arg, Path config) {
        if (arg.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return new RecordingLayout(config.resolve(GatewayMain.RECORDINGS_DIR)).dayDir(Market.KRX, LocalDate.parse(arg));
        }
        return Path.of(arg);
    }

    private static void ensureNotStopped(StopCheck gate) {
        gate.stopReason().ifPresent(reason -> {
            throw new StoppedException(reason);
        });
    }

    enum Verdict {
        SAME(0, "**동일**"),
        DIFFERENT(1, "**다름**"),
        INCOMPLETE(3, "**불완전**(두 회차는 같지만 정상 종료가 아니거나, 마지막 줄이 잘렸거나, 0건이다. 완료 증거로 쓰지 않는다)");

        final int exitCode;
        final String label;

        Verdict(int exitCode, String label) {
            this.exitCode = exitCode;
            this.label = label;
        }
    }

    /**
     * 녹화일 하나를 두 번 리플레이해 비교하고 종목별 요약을 만든다. 회차마다 녹화를 따로 줄 단위로 읽고, 줄·이벤트 목록은
     * 들고 있지 않는다(힙은 종목 수에만 따른다, D-117).
     */
    static Report check(Path dayDir) {
        return check(dayDir, SymbolNames.empty());
    }

    static Report check(Path dayDir, SymbolNames names) {
        return check(dayDir, names, StopCheck.NONE);
    }

    /** 정지 확인을 회차 시작마다, 회차 안에서 {@link #CHECK_EVERY}줄마다 묻는다. 멈추라면 {@link StoppedException}. */
    static Report check(Path dayDir, SymbolNames names, StopCheck gate) {
        return check(dayDir, names, gate, CHECK_EVERY);
    }

    static Report check(Path dayDir, SymbolNames names, StopCheck gate, int checkEvery) {
        Market market = Market.valueOf(dayDir.getParent().getFileName().toString());
        LocalDate date = LocalDate.parse(dayDir.getFileName().toString());
        Optional<TradingSession> session = regularSession(market, date);

        Run first = replayOnce(dayDir, session, gate, checkEvery);
        Run second = replayOnce(dayDir, session, gate, checkEvery);
        return new Report(dayDir, first.meta(), first.truncated(), first.eventCount(), first.gapCount(), session,
                first.digest(), second.digest(), first.replayed(), second.replayed(), first.symbols(), names,
                first.unmarkedRestarts());
    }

    private static Optional<TradingSession> regularSession(Market market, LocalDate date) {
        try {
            return ExchangeCalendar.bundled(market).session(date);
        } catch (CalendarCoverageException e) {
            return Optional.empty();
        }
    }

    private record Run(Optional<RecordingMeta> meta, boolean truncated, long eventCount, long gapCount, String digest,
            long replayed, Map<Symbol, SymbolSummary> symbols, List<RecordingMeta.Segment> unmarkedRestarts) {}

    /**
     * 한 회차. 녹화를 두 번 줄 단위로 읽는다(하루를 메모리에 올리지 않음, D-117): 첫 읽기로 종목을 모으고, 두 번째 읽기로
     * 리플레이 소스가 이벤트를 보낸다. 두 읽기의 요약(메타·건수·잘림·마지막 순번 등)이 다르면 읽는 사이 녹화가 바뀐 것이라
     * 결과 없이 멈춘다.
     */
    private static Run replayOnce(Path dayDir, Optional<TradingSession> session, StopCheck gate, int checkEvery) {
        ensureNotStopped(gate);
        Set<Symbol> symbols = new TreeSet<>();
        long[] read = {0};
        RecordedDayStream.Summary day = RecordedDayStream.read(dayDir, line -> {
            symbols.add(line.event().symbol());
            if (++read[0] % checkEvery == 0) {
                ensureNotStopped(gate);
            }
        });
        ensureNotStopped(gate);
        ZoneId zone = day.meta().map(m -> ZoneId.of(m.zone()))
                .orElse(session.map(TradingSession::zone).orElse(ZoneId.of("UTC")));
        Map<Symbol, SymbolSummary> summaries = new TreeMap<>();
        MessageDigest sha = sha256();
        long[] count = {0};
        RecordedReplaySource source = RecordedReplaySource.streaming(dayDir);
        source.subscribe(symbols, e -> {
            if (++count[0] % checkEvery == 0) {
                ensureNotStopped(gate);
            }
            sha.update((e + "\n").getBytes(StandardCharsets.UTF_8));
            summaries.computeIfAbsent(e.symbol(), s -> new SymbolSummary(zone, session)).add(e);
        });
        RecordedDayStream.Summary replayed = source.replay();
        if (!replayed.equals(day)) {
            throw new IllegalStateException("녹화가 읽는 사이에 바뀌었습니다(아직 쓰이는 중일 수 있음): " + dayDir);
        }
        return new Run(day.meta(), day.truncated(), day.eventCount(), day.gapCount(),
                HexFormat.of().formatHex(sha.digest()), count[0], summaries, day.unmarkedRestarts());
    }

    private static String localTime(String isoInstant, String zone) {
        return LocalTime.ofInstant(Instant.parse(isoInstant), ZoneId.of(zone)).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    /** 종목 하나의 하루 요약. 체결 순서는 녹화 순번 순서다. 정규장 구간을 모르면 모든 체결을 정규장 칸에 넣는다. */
    static final class SymbolSummary {
        private final ZoneId zone;
        private final Optional<TradingSession> session;
        long trades;
        long books;
        long gaps;
        long volume;
        long outsideTrades;
        long outsideVolume;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        Instant firstTrade;
        Instant lastTrade;

        SymbolSummary(ZoneId zone, Optional<TradingSession> session) {
            this.zone = zone;
            this.session = session;
        }

        private boolean regular(Instant t) {
            return session.map(s -> !t.isBefore(s.openInstant()) && t.isBefore(s.closeInstant().plus(CLOSING_PRINT_GRACE)))
                    .orElse(true);
        }

        void add(MarketEvent e) {
            switch (e) {
                case TradeTick t -> addTrade(t);
                case OrderBookSnapshot b -> books++;
                case DataGap g -> gaps++;
                case InstrumentStatusChanged c -> { }
            }
        }

        private void addTrade(TradeTick t) {
            if (!regular(t.exchangeTime())) {
                outsideTrades++;
                outsideVolume += t.quantity();
                return;
            }
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

        private String time(Instant t) {
            return t == null ? "-" : LocalTime.ofInstant(t, zone).truncatedTo(ChronoUnit.SECONDS).toString();
        }

        private static String px(BigDecimal p) {
            return p == null ? "-" : p.toPlainString();
        }

        String row(Symbol s, SymbolNames names) {
            String name = names.name(s).map(n -> n.replace("|", "\\|")).orElse("–");
            return "| " + s.code() + " | " + name + " | " + trades + " | " + px(open) + " | " + px(high) + " | " + px(low) + " | "
                    + px(close) + " | " + volume + " | " + time(firstTrade) + " | " + time(lastTrade) + " | "
                    + outsideTrades + " / " + outsideVolume + " | " + books + " | " + gaps + " |";
        }
    }

    record Report(Path dir, Optional<RecordingMeta> meta, boolean truncated, long eventCount, long gapCount,
            Optional<TradingSession> session, String firstDigest, String secondDigest, long firstCount, long secondCount,
            Map<Symbol, SymbolSummary> symbols, SymbolNames names, List<RecordingMeta.Segment> unmarkedRestarts) {

        boolean complete() {
            return meta.map(RecordingMeta::complete).orElse(false);
        }

        /** {@link RecordedDayStream}이 파일 해시를 메타와 대조했는지. */
        boolean hashVerified() {
            return complete() && !meta.get().eventsSha256().isEmpty();
        }

        Verdict verdict() {
            if (firstCount != secondCount || !firstDigest.equals(secondDigest)) {
                return Verdict.DIFFERENT;
            }
            if (!complete() || truncated || eventCount == 0) {
                return Verdict.INCOMPLETE;
            }
            return Verdict.SAME;
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
                        .append(", eventsSha256 `").append(m.eventsSha256().isEmpty() ? "-" : m.eventsSha256()).append('`')
                        .append(hashVerified() ? " (파일과 일치 확인됨)" : " (파일 대조 안 함: 정상 종료 녹화만 대조)").append('\n');
                b.append("- 출처: ").append(new TreeMap<>(m.sources())).append('\n');
                b.append("- 녹화 구간 수(재기동 포함): ").append(m.segments().size()).append('\n');
                if (!unmarkedRestarts.isEmpty()) {
                    b.append("- 재기동 공백 표시 없음(D-065 이전 녹화): 구간 시작 ");
                    for (int i = 0; i < unmarkedRestarts.size(); i++) {
                        RecordingMeta.Segment s = unmarkedRestarts.get(i);
                        b.append(i == 0 ? "" : ", ").append(localTime(s.startedAt(), m.zone())).append("(seq ")
                                .append(s.firstSeq()).append(')');
                    }
                    b.append(". 이 경계 앞 시간은 이벤트 흐름에 끊김 표시 없이 비어 있다\n");
                }
            } else {
                b.append("- 메타: 없음(정상 종료되지 않음, 파일 해시 대조 안 함)\n");
            }
            b.append("- 마지막 줄 잘림: ").append(truncated ? "예" : "아니오").append('\n');
            b.append("- 이벤트 수: ").append(eventCount).append(", 끊김 표시: ").append(gapCount).append('\n');
            b.append("- 리플레이 1회차: ").append(firstCount).append("건, SHA-256 `").append(firstDigest).append("`\n");
            b.append("- 리플레이 2회차: ").append(secondCount).append("건, SHA-256 `").append(secondDigest).append("`\n");
            b.append("- 판정: ").append(verdict().label).append("\n\n");
            b.append("## 종목별 체결 요약 (원본 대조용, 시각은 거래소 현지)\n\n");
            b.append(session.map(s -> "정규장 구간 " + s.open() + "~" + s.close() + "(종가 단일가 체결용 "
                    + CLOSING_PRINT_GRACE.toMinutes() + "분 포함) 체결만 시가~마지막 체결 칸에 넣었다. 그 밖의 체결은 "
                    + "\"정규장 밖\" 칸에 건수 / 수량으로 적었다. 대조 상대는 HTS의 **KRX 단독 정규장** 일봉이다"
                    + "(NXT 통합·시간외 포함 값과 비교하지 않는다).")
                    .orElse("캘린더에 이 날짜의 정규장 구간이 없어(휴장·수록 범위 밖) 모든 체결을 집계했다."));
            b.append("\n\n| 종목 | 종목명 | 체결 수 | 시가 | 고가 | 저가 | 종가 | 체결 수량 합 | 첫 체결 | 마지막 체결 | 정규장 밖 | 호가 수 | 끊김 |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            symbols.forEach((s, sum) -> b.append(sum.row(s, names)).append('\n'));
            return b.toString();
        }
    }
}
