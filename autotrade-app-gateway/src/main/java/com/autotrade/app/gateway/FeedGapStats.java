package com.autotrade.app.gateway;

import com.autotrade.domain.SessionPhase;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.calendar.TradingSession;
import com.autotrade.marketdata.recording.RecordedDayStream;
import com.autotrade.marketdata.recording.RecordingCodec;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.recording.RecordingMeta;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.marketdata.symbols.SymbolNameFiles;
import com.autotrade.marketdata.symbols.SymbolNames;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 녹화일들의 시세 수신 간격 분포(ADR-016 G0, D-147). 실전 엔진 입력 계약(ADR-016 2.6)의 두 기준에 근거를 낸다.
 *
 * <ul>
 *   <li><b>종목 단위</b>("조용함" T_quiet, 기본 {@code market-data.symbol-gap}): 한 종목의 이벤트(체결·호가·상태) 사이 간격.</li>
 *   <li><b>소스 단위</b>("소스 끊김" 3초, 장애표 #1): 한 소스({@code src})가 보낸 모든 종목 이벤트 사이 간격.</li>
 * </ul>
 *
 * <p>간격은 게이트웨이 수신 시각({@code rx})으로 잰다. 엔진이 보는 것이 게이트웨이가 받은 시각이라서다(거래소 시각은 소스마다
 * 찍는 방식이 달라 쓰지 않는다). 매매 단계({@link KrxMarketRules#sessionPhase}: 시가 단일가·접속 매매·종가 단일가)마다 따로
 * 세며(risk-ops PR #245 N3), 단계 경계를 넘는 간격은 세지 않는다. 한 단계 안에서:
 *
 * <ul>
 *   <li><b>간격 분포</b>: 이어진 두 이벤트 사이, 마지막 이벤트 → 단계 끝(녹화가 단계 끝까지 이어졌을 때), 그리고 그 종목·소스가
 *       단계 전에 이벤트가 있었는데 단계 내내 하나도 없으면 단계 길이 전체("단계 내내 없음"으로도 센다). 엔진이 단계 안에서
 *       "지금 − 마지막 이벤트"로 재는 조용한 시간과 같은 것이다.</li>
 *   <li><b>단계 시작 → 첫 이벤트</b>는 분포에 넣지 않고 따로 적는다. KRX는 시가 단일가 접수 마감을 임의로 늦추고
 *       ({@link KrxMarketRules#opensWithAuction}), 장중에 감시 종목에 들어온 종목은 시작이 늦어서, 엔진이 단계 시작을 따로 다룰지는
 *       G4에서 정한다.</li>
 *   <li><b>뺀 간격</b>: 끊김 표시({@code GAP}, 이유 무관)와 녹화 재기동 경계(메타 구간)를 지나는 간격은 장애이지 조용함이 아니므로
 *       분포에서 빼고 건수만 적는다. 소스 단위는 그 소스가 낸 끊김 표시에서 끊는다.</li>
 *   <li><b>VI 중</b>: 종목이 변동성 완화장치·정지 상태({@code STATUS})일 때 시작한 간격은 종목 단위 분포에서 빼고 따로 적는다.</li>
 * </ul>
 *
 * <p>분포는 고정 칸 히스토그램(1초 미만 10ms, 10초 미만 100ms, 600초 미만 1초, 그 위 한 칸)으로 모아 녹화 기간이 길어도 메모리가
 * 종목 수에만 따른다. 백분위는 그 칸의 위 경계(올림, 보수적)로 적고, 최대와 기준값 초과 횟수는 정확한 값이다. 백분위는
 * 가까운 순위 방식이다. 같은 녹화면 같은 보고서가 나온다(현재 시각을 쓰지 않음).
 *
 * <p>사용: {@code ./gradlew :autotrade-app-gateway:feedGaps --args="2026-10-26..2026-10-30 --out=보고서.md"}. 날짜(설정 폴더의
 * {@code recordings/KRX/<날짜>}), 날짜 범위({@code from..to}, 범위 안 KRX 거래일), 녹화일 폴더를 여러 개 줄 수 있다.
 * 리플레이 점검과 같은 정지 확인({@link ReplayCheckGate}, D-085)을 쓴다: 정지 요청 파일이 있거나 장 시간 보호 구간이면 시작하지 않고,
 * 실행 중이면 멈춘다. 녹화 파일만 읽고 네트워크·주문을 쓰지 않는다. 게이트웨이·녹화 동작은 바꾸지 않는다.
 * 종료 코드 0: 보고서 만듦, 2: 입력 오류·읽기 실패·녹화 없음, 3: 보고서는 만들었으나 정상 종료되지 않았거나 잘린 녹화일이 있음,
 * 4: 정지 확인으로 멈춤(보고서 안 씀).
 */
public final class FeedGapStats {

    /** 초과 횟수를 세는 기준값(초). 소스 3초(장애표 #1)와 T_quiet 후보(기본 30초)를 덮는다. */
    static final int[] THRESHOLDS_S = {1, 2, 3, 5, 10, 15, 20, 30, 60, 120};

    /** 종목별 표에 보이는 기준값(초). */
    static final int[] SYMBOL_TABLE_THRESHOLDS_S = {10, 30, 60};

    static final int EXIT_INCOMPLETE = 3;

    /** 잴 매매 단계. 순서는 하루 안 순서와 같다. */
    enum Window {
        OPENING_AUCTION("시가 단일가"),
        CONTINUOUS("접속 매매"),
        CLOSING_AUCTION("종가 단일가");

        final String label;

        Window(String label) {
            this.label = label;
        }

        static Optional<Window> of(SessionPhase phase) {
            return switch (phase) {
                case OPENING_AUCTION -> Optional.of(OPENING_AUCTION);
                case CONTINUOUS -> Optional.of(CONTINUOUS);
                case CLOSING_AUCTION -> Optional.of(CLOSING_AUCTION);
                case CLOSED -> Optional.empty();
            };
        }
    }

    private static final int W = Window.values().length;
    /** 단계 밖(장전). 단계 순서 비교용. */
    private static final int BEFORE = -1;
    /** 단계 밖(폐장 뒤). */
    private static final int AFTER = W;

    private FeedGapStats() {}

    // ---------------------------------------------------------------- 명령

    /** 명령 진입({@link FeedGapStatsMain}). */
    static int run(String[] args, Clock clock, Path config, PrintStream out, PrintStream err) {
        List<String> inputs = new ArrayList<>();
        Path outFile = null;
        boolean allow = false;
        for (String a : args) {
            if (List.of("--allow-during-session", "--allow-during-session=", "--allow-during-session=true").contains(a)) {
                allow = true;
            } else if (a.startsWith("--allow-during-session")) {
                err.println("--allow-during-session은 값이 없거나 true여야 합니다(풀지 않으려면 옵션을 빼세요): " + a);
                return 2;
            } else if (a.startsWith("--out=") && a.length() > "--out=".length()) {
                outFile = Path.of(a.substring("--out=".length()));
            } else if (a.startsWith("--")) {
                err.println("모르는 옵션: " + a);
                return 2;
            } else {
                inputs.add(a);
            }
        }
        if (inputs.isEmpty()) {
            err.println("사용법: feedGaps <yyyy-MM-dd | yyyy-MM-dd..yyyy-MM-dd | 녹화일 폴더>... [--out=보고서.md] [--allow-during-session]");
            return 2;
        }
        ReplayCheckGate gate = ReplayCheckGate.of(clock, config, allow);
        err.println("[feedGaps] 정지 요청 파일 확인 위치: " + gate.stopRequest());
        if (allow) {
            err.println("[feedGaps] 장 시간 보호 해제됨(실전 엔진을 끈 날만). 정지 요청 파일은 계속 확인합니다");
        }
        Optional<String> stop = gate.stopReason();
        if (stop.isPresent()) {
            err.println("수신 간격 측정을 시작하지 않습니다: " + stop.get());
            return ReplayCheck.EXIT_STOPPED;
        }
        SymbolNameFiles.Loaded loaded = SymbolNameFiles.load(config);
        loaded.warnings().forEach(err::println);
        return run(inputs, outFile, config, gate, loaded.names(), out, err);
    }

    static int run(List<String> inputs, Path outFile, Path config, ReplayCheck.StopCheck gate, SymbolNames names,
            PrintStream out, PrintStream err) {
        try {
            Resolved days = resolve(inputs, config);
            if (days.dirs().isEmpty()) {
                err.println("읽을 녹화일이 없습니다. 없는 녹화일: " + days.missing());
                return 2;
            }
            Report report = measure(days.dirs(), days.missing(), names, gate, ReplayCheck.CHECK_EVERY);
            String text = report.toMarkdown();
            out.print(text);
            if (outFile != null) {
                Files.writeString(outFile, text, StandardCharsets.UTF_8);
            }
            return report.allComplete() ? 0 : EXIT_INCOMPLETE;
        } catch (ReplayCheck.StoppedException e) {
            err.println("수신 간격 측정을 멈췄습니다(보고서를 쓰지 않음): " + e.getMessage());
            return ReplayCheck.EXIT_STOPPED;
        } catch (IOException | RuntimeException e) {
            err.println("수신 간격 측정 실패: " + e.getMessage());
            return 2;
        }
    }

    record Resolved(List<Path> dirs, List<String> missing) {}

    /** 입력을 녹화일 폴더로 바꾼다. 날짜·범위는 설정 폴더의 KRX 녹화를 본다. 같은 폴더는 한 번만, 날짜 순서로. */
    static Resolved resolve(List<String> inputs, Path config) {
        RecordingLayout layout = new RecordingLayout(config.resolve(GatewayMain.RECORDINGS_DIR));
        ExchangeCalendar calendar = ExchangeCalendar.bundled(Market.KRX);
        TreeMap<String, Path> dirs = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        for (String in : inputs) {
            List<LocalDate> dates = new ArrayList<>();
            if (in.matches("\\d{4}-\\d{2}-\\d{2}\\.\\.\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate from = LocalDate.parse(in.substring(0, 10));
                LocalDate to = LocalDate.parse(in.substring(12));
                if (to.isBefore(from)) {
                    throw new IllegalArgumentException("범위 끝이 시작보다 앞입니다: " + in);
                }
                dates.addAll(calendar.tradingDays(from, to));
            } else if (in.matches("\\d{4}-\\d{2}-\\d{2}")) {
                dates.add(LocalDate.parse(in));
            } else {
                Path dir = Path.of(in).toAbsolutePath().normalize();
                if (!Files.isDirectory(dir) || dir.getParent() == null) {
                    throw new IllegalArgumentException("녹화일 폴더가 아닙니다: " + in);
                }
                dirs.put(dir.getParent().getFileName() + "/" + dir.getFileName(), dir);
                continue;
            }
            for (LocalDate d : dates) {
                Path dir = layout.dayDir(Market.KRX, d);
                if (Files.isDirectory(dir)) {
                    dirs.put("KRX/" + d, dir);
                } else {
                    missing.add(d.toString());
                }
            }
        }
        return new Resolved(List.copyOf(dirs.values()), List.copyOf(new TreeSet<>(missing)));
    }

    // ---------------------------------------------------------------- 측정

    /** 녹화일들을 줄 단위로 한 번씩 읽어 분포를 모은다. 정지 확인을 녹화일마다, 그 안에서 {@code checkEvery}줄마다 묻는다. */
    static Report measure(List<Path> dayDirs, List<String> missing, SymbolNames names, ReplayCheck.StopCheck gate,
            int checkEvery) {
        Totals totals = new Totals();
        List<DayInfo> days = new ArrayList<>();
        for (Path dir : dayDirs) {
            ensureNotStopped(gate);
            days.add(measureDay(dir, totals, gate, checkEvery));
        }
        ensureNotStopped(gate);
        return new Report(days, missing, totals, names);
    }

    private static void ensureNotStopped(ReplayCheck.StopCheck gate) {
        gate.stopReason().ifPresent(reason -> {
            throw new ReplayCheck.StoppedException(reason);
        });
    }

    /** 하루의 단계 경계. {@code start[w]} 이상 {@code end[w]} 미만이 단계 w다. */
    record Windows(Instant[] start, Instant[] end) {
        long micros(int w) {
            return toMicros(end[w]) - toMicros(start[w]);
        }
    }

    /**
     * 단계 경계를 {@link KrxMarketRules#sessionPhase}로 1초씩 훑어 찾는다(단계 판정의 원본을 하나로 둔다). KRX 단계 경계는 분 단위라
     * 1초 해상도면 정확하다 {@code [사실]}(KrxMarketRules: 개장 30분 전, 폐장 10분 전, 폐장).
     */
    static Windows windows(KrxMarketRules rules, TradingSession session) {
        Instant[] start = new Instant[W];
        Instant[] end = new Instant[W];
        Instant from = session.openInstant().minus(Duration.ofHours(2));
        Instant to = session.closeInstant().plus(Duration.ofHours(1));
        int prev = BEFORE;
        for (Instant t = from; !t.isAfter(to); t = t.plusSeconds(1)) {
            int w = Window.of(rules.sessionPhase(t)).map(Enum::ordinal).orElse(prev < 0 ? BEFORE : AFTER);
            if (w != prev) {
                if (prev >= 0 && prev < W) {
                    end[prev] = t;
                }
                if (w >= 0 && w < W) {
                    start[w] = t;
                }
                prev = w;
            }
        }
        for (int w = 0; w < W; w++) {
            if (start[w] == null || end[w] == null) {
                throw new IllegalStateException(session.date() + " 매매 단계 경계를 찾지 못했습니다: " + Window.values()[w]);
            }
        }
        return new Windows(start, end);
    }

    private static DayInfo measureDay(Path dir, Totals totals, ReplayCheck.StopCheck gate, int checkEvery) {
        Market market = Market.valueOf(dir.getParent().getFileName().toString());
        if (market != Market.KRX) {
            throw new IllegalArgumentException("KRX 녹화만 잽니다(미국 단계 규칙은 아직 없음): " + dir);
        }
        LocalDate date = LocalDate.parse(dir.getFileName().toString());
        ExchangeCalendar calendar = ExchangeCalendar.bundled(Market.KRX);
        TradingSession session = calendar.session(date)
                .orElseThrow(() -> new IllegalArgumentException("거래일이 아닙니다(캘린더 휴장): " + date));
        KrxMarketRules rules = new KrxMarketRules(calendar, s -> InstrumentKind.STOCK);
        Windows win = windows(rules, session);
        Optional<RecordingMeta> meta = RecordedDayStream.readMeta(dir);
        TreeSet<Long> boundaries = new TreeSet<>();
        meta.ifPresent(m -> m.segments().stream().skip(1).forEach(s -> boundaries.add(s.firstSeq())));

        Day day = new Day(date, win, totals);
        long[] read = {0};
        RecordedDayStream.Summary summary = RecordedDayStream.read(dir, line -> {
            if (++read[0] % checkEvery == 0) {
                ensureNotStopped(gate);
            }
            Long boundary = boundaries.isEmpty() ? null : boundaries.first();
            if (boundary != null && line.seq() >= boundary) {
                boundaries.headSet(line.seq(), true).clear();
                day.resetAll(day.lastLineRx == null ? line.event().receivedAt() : day.lastLineRx, line.event().receivedAt());
            }
            day.accept(line, rules);
        });
        day.finish(summary.lastReceivedAt());
        return new DayInfo(date, summary.meta(), summary.truncated(), summary.eventCount(), summary.gapCount(),
                meta.map(m -> m.segments().size()).orElse(0), day.firstRx, summary.lastReceivedAt(), session.zone(),
                new TreeMap<>(day.sourceLines), day.statusLines);
    }

    /** 하루 읽기 상태. 종목·소스별 이어 보기(Chain)를 들고 있다가 하루 끝에 닫는다. 날을 넘겨 잇지 않는다. */
    private static final class Day {
        final LocalDate date;
        final Windows win;
        final Totals totals;
        final Map<Symbol, Chain> symbols = new LinkedHashMap<>();
        final Map<String, Chain> sources = new LinkedHashMap<>();
        final Map<String, Long> sourceLines = new LinkedHashMap<>();
        /** 녹화 재기동으로 빈 시간이 그 단계와 겹쳤는지(그 단계 시작 → 첫 이벤트·단계 내내 없음을 세지 않는다). */
        final boolean[] globalResetIn = new boolean[W];
        Instant firstRx;
        Instant lastLineRx;
        long statusLines;

        Day(LocalDate date, Windows win, Totals totals) {
            this.date = date;
            this.win = win;
            this.totals = totals;
        }

        int window(Instant rx, KrxMarketRules rules) {
            Optional<Window> w = Window.of(rules.sessionPhase(rx));
            if (w.isPresent()) {
                return w.get().ordinal();
            }
            return rx.isBefore(win.start[0]) ? BEFORE : AFTER;
        }

        /** 끊긴 시간 {@code [from, to]}와 겹치는 단계에 표시한다. */
        void markOverlap(boolean[] marks, long from, long to) {
            for (int w = 0; w < W; w++) {
                if (toMicros(win.start[w]) <= to && toMicros(win.end[w]) > from) {
                    marks[w] = true;
                }
            }
        }

        /** 녹화 재기동: 앞 구간 마지막 줄 수신 시각부터 새 구간 첫 줄까지 모든 이어 보기를 끊는다. */
        void resetAll(Instant from, Instant to) {
            long f = toMicros(from);
            long t = toMicros(to);
            markOverlap(globalResetIn, f, t);
            symbols.values().forEach(c -> c.reset(f, t));
            sources.values().forEach(c -> c.reset(f, t));
        }

        void accept(RecordingCodec.Line line, KrxMarketRules rules) {
            Instant rx = line.event().receivedAt();
            if (firstRx == null) {
                firstRx = rx;
            }
            int w = window(rx, rules);
            lastLineRx = rx;
            sourceLines.merge(line.sourceId(), 1L, Long::sum);
            if (line.event() instanceof DataGap g) {
                long from = Math.min(toMicros(g.gapStart()), toMicros(rx));
                long to = toMicros(rx);
                symbols.computeIfAbsent(line.event().symbol(), s -> new Chain(this, totals.symbolSink(s))).reset(from, to);
                Chain src = sources.get(line.sourceId());
                if (src != null) {
                    src.reset(from, to);
                } else {
                    // 시세 소스가 아닌 출처(녹화기 "RECORDER"의 큐 넘침·재기동 표시)는 어느 소스 이벤트를 버렸는지 모르므로
                    // 모든 소스 이어 보기를 끊는다(strategy-researcher PR #256 R1). 이 출처로 소스 분포를 만들지 않는다.
                    sources.values().forEach(c -> c.reset(from, to));
                }
                return;
            }
            Chain sym = symbols.computeIfAbsent(line.event().symbol(), s -> new Chain(this, totals.symbolSink(s)));
            Chain src = sources.computeIfAbsent(line.sourceId(), s -> new Chain(this, totals.sourceSink(s, date)));
            boolean vi = sym.inVi;
            if (line.event() instanceof InstrumentStatusChanged st) {
                statusLines++;
                vi = st.status() == InstrumentStatus.VOLATILITY_INTERRUPTION || st.status() == InstrumentStatus.HALTED;
            }
            sym.event(toMicros(rx), w, rx);
            sym.inVi = vi;
            src.event(toMicros(rx), w, rx);
        }

        void finish(Instant lastRx) {
            long last = lastRx == null ? Long.MIN_VALUE : toMicros(lastRx);
            long first = firstRx == null ? Long.MAX_VALUE : toMicros(firstRx);
            symbols.values().forEach(c -> c.finish(first, last));
            sources.values().forEach(c -> c.finish(first, last));
        }
    }

    /** 종목 또는 소스 하나의 하루 이어 보기. */
    private static final class Chain {
        final Day day;
        final Sink sink;
        long firstT = Long.MIN_VALUE;
        long lastT = Long.MIN_VALUE;
        int lastW = BEFORE;
        /** 마지막 이벤트 뒤로 끊김·재기동이 없었는지. */
        boolean valid;
        /** 마지막 이벤트 때 VI 상태였는지(종목만). */
        boolean inVi;
        final long[] eventsIn = new long[W];
        /** 끊긴 시간이 겹쳤거나, 끊긴 채(마지막 이벤트 뒤 끊김) 시작한 단계. 시작 → 첫 이벤트·단계 내내 없음을 세지 않는다. */
        final boolean[] resetIn = new boolean[W];
        /** 마지막 끊김의 끝(수신 시각)이 든 단계 순서. */
        int resetW = BEFORE;

        Chain(Day day, Sink sink) {
            this.day = day;
            this.sink = sink;
        }

        void reset(long from, long to) {
            if (valid && lastW >= 0 && lastW < W) {
                sink.excluded(lastW);
            }
            valid = false;
            inVi = false;
            day.markOverlap(resetIn, from, to);
            resetW = BEFORE;
            for (int w = 0; w < W; w++) {
                if (toMicros(day.win.start[w]) <= to) {
                    resetW = w;
                }
            }
            if (to >= toMicros(day.win.end[W - 1])) {
                resetW = AFTER;
            }
        }

        /** 끊긴 채로 시작한 단계(마지막 끊김 단계 뒤 ~ {@code upTo})를 표시한다(architect PR #256 R1). */
        private void markInvalidUpTo(int upTo) {
            for (int k = Math.max(0, resetW + 1); k <= Math.min(upTo, W - 1); k++) {
                resetIn[k] = true;
            }
        }

        void event(long t, int w, Instant at) {
            boolean first = firstT == Long.MIN_VALUE;
            if (first) {
                firstT = t;
            }
            if (lastT != Long.MIN_VALUE && t < lastT) {
                day.totals.backwards++;
            }
            if (valid && lastW == w && w >= 0 && w < W) {
                sink.interval(w, Math.max(0, t - lastT), fromMicros(lastT), inVi);
            } else {
                if (valid && lastW >= 0 && lastW < W && w > lastW) {
                    trailing(lastW);
                }
                if (!valid && !first) {
                    markInvalidUpTo(w);
                }
                if (valid && w >= 0 && w < W && w > lastW && eventsIn[w] == 0) {
                    // 단계 경계를 지난 엔진 쪽 나이: 앞 단계(또는 장전) 마지막 이벤트 → 이 단계 첫 이벤트(strategy-researcher R2)
                    sink.cross(w, Math.max(0, t - lastT), fromMicros(lastT));
                }
                if (w >= 0 && w < W && eventsIn[w] == 0) {
                    long start = toMicros(day.win.start[w]);
                    long dayFirst = toMicros(day.firstRx);
                    if (!resetIn[w] && !day.globalResetIn[w] && dayFirst <= start) {
                        sink.leading(w, Math.max(0, t - start), day.win.start[w]);
                    } else {
                        sink.leadingSkipped(w);
                    }
                }
            }
            lastT = Math.max(lastT, t);
            lastW = w;
            valid = true;
            if (w >= 0 && w < W) {
                eventsIn[w]++;
            }
        }

        private void trailing(int w) {
            long end = toMicros(day.win.end[w]);
            sink.interval(w, Math.max(0, end - lastT), fromMicros(lastT), inVi);
        }

        void finish(long dayFirst, long dayLast) {
            if (!valid && firstT != Long.MIN_VALUE) {
                markInvalidUpTo(W - 1);
            }
            if (valid && lastW >= 0 && lastW < W) {
                if (dayLast >= toMicros(day.win.end[lastW])) {
                    trailing(lastW);
                } else {
                    sink.excluded(lastW);
                }
            }
            for (int w = 0; w < W; w++) {
                long start = toMicros(day.win.start[w]);
                long end = toMicros(day.win.end[w]);
                if (eventsIn[w] == 0 && firstT != Long.MIN_VALUE && firstT < start && !resetIn[w] && !day.globalResetIn[w]
                        && dayFirst <= start && dayLast >= end) {
                    sink.silent(w, day.win.micros(w), day.win.start[w]);
                }
            }
            sink.dayDone(firstT != Long.MIN_VALUE);
        }
    }

    /** 이어 보기가 낸 값을 받는 곳. */
    private interface Sink {
        void interval(int w, long micros, Instant from, boolean vi);

        void leading(int w, long micros, Instant at);

        void leadingSkipped(int w);

        void silent(int w, long micros, Instant at);

        void excluded(int w);

        void cross(int w, long micros, Instant from);

        /** @param hadEvents 그날 이벤트가 하나라도 있었는지(끊김 표시만 있으면 종목·일로 세지 않음) */
        void dayDone(boolean hadEvents);
    }

    /** 단계별 묶음 하나(분포·시작 → 첫 이벤트·VI 중·뺀 간격·단계 내내 없음). */
    static final class Stats {
        final Hist[] main = new Hist[W];
        final Hist[] leading = new Hist[W];
        final Hist[] vi = new Hist[W];
        /** 단계 경계를 지난 나이(앞 단계 마지막 이벤트 → 이 단계 첫 이벤트). */
        final Hist[] cross = new Hist[W];
        final long[] excluded = new long[W];
        final long[] leadingSkipped = new long[W];
        final long[] silent = new long[W];

        Stats() {
            for (int w = 0; w < W; w++) {
                main[w] = new Hist();
                leading[w] = new Hist();
                vi[w] = new Hist();
                cross[w] = new Hist();
            }
        }
    }

    /** 여러 녹화일에 걸친 합계. */
    static final class Totals {
        final Map<String, Stats> sources = new TreeMap<>();
        /** 날짜·소스별 접속 매매 분포. */
        final Map<LocalDate, Map<String, Hist>> sourceDaily = new TreeMap<>();
        final Map<Symbol, Stats> symbols = new TreeMap<>();
        final Map<Symbol, Long> symbolDays = new TreeMap<>();
        final Stats allSymbols = new Stats();
        /** 기준값마다 접속 매매에서 그 기준을 한 번이라도 넘은 종목·일 수. */
        final long[] symbolDaysOver = new long[THRESHOLDS_S.length];
        long symbolDayCount;
        long backwards;

        Sink symbolSink(Symbol s) {
            Stats st = symbols.computeIfAbsent(s, k -> new Stats());
            long[] dayOver = new long[THRESHOLDS_S.length];
            return new Sink() {
                @Override
                public void interval(int w, long micros, Instant from, boolean viState) {
                    if (viState) {
                        st.vi[w].add(micros, from);
                        allSymbols.vi[w].add(micros, from);
                        return;
                    }
                    st.main[w].add(micros, from);
                    allSymbols.main[w].add(micros, from);
                    if (w == Window.CONTINUOUS.ordinal()) {
                        countOver(dayOver, micros);
                    }
                }

                @Override
                public void leading(int w, long micros, Instant at) {
                    st.leading[w].add(micros, at);
                    allSymbols.leading[w].add(micros, at);
                }

                @Override
                public void leadingSkipped(int w) {
                    st.leadingSkipped[w]++;
                    allSymbols.leadingSkipped[w]++;
                }

                @Override
                public void silent(int w, long micros, Instant at) {
                    st.silent[w]++;
                    allSymbols.silent[w]++;
                    interval(w, micros, at, false);
                }

                @Override
                public void excluded(int w) {
                    st.excluded[w]++;
                    allSymbols.excluded[w]++;
                }

                @Override
                public void cross(int w, long micros, Instant from) {
                    st.cross[w].add(micros, from);
                    allSymbols.cross[w].add(micros, from);
                }

                @Override
                public void dayDone(boolean hadEvents) {
                    if (hadEvents) {
                        symbolDays.merge(s, 1L, Long::sum);
                        symbolDayCount++;
                    }
                    for (int i = 0; i < dayOver.length; i++) {
                        if (hadEvents && dayOver[i] > 0) {
                            symbolDaysOver[i]++;
                        }
                        dayOver[i] = 0;
                    }
                }
            };
        }

        Sink sourceSink(String src, LocalDate date) {
            Stats st = sources.computeIfAbsent(src, k -> new Stats());
            Hist daily = sourceDaily.computeIfAbsent(date, d -> new TreeMap<>()).computeIfAbsent(src, k -> new Hist());
            return new Sink() {
                @Override
                public void interval(int w, long micros, Instant from, boolean vi) {
                    st.main[w].add(micros, from);
                    if (w == Window.CONTINUOUS.ordinal()) {
                        daily.add(micros, from);
                    }
                }

                @Override
                public void leading(int w, long micros, Instant at) {
                    st.leading[w].add(micros, at);
                }

                @Override
                public void leadingSkipped(int w) {
                    st.leadingSkipped[w]++;
                }

                @Override
                public void silent(int w, long micros, Instant at) {
                    st.silent[w]++;
                    interval(w, micros, at, false);
                }

                @Override
                public void excluded(int w) {
                    st.excluded[w]++;
                }

                @Override
                public void cross(int w, long micros, Instant from) {
                    st.cross[w].add(micros, from);
                }

                @Override
                public void dayDone(boolean hadEvents) {}
            };
        }

        private static void countOver(long[] over, long micros) {
            for (int i = 0; i < THRESHOLDS_S.length; i++) {
                if (micros > THRESHOLDS_S[i] * 1_000_000L) {
                    over[i]++;
                }
            }
        }
    }

    // ---------------------------------------------------------------- 히스토그램

    /**
     * 간격 히스토그램(마이크로초). 칸: 1초 미만 10ms(100칸), 10초 미만 100ms(90칸), 600초 미만 1초(590칸), 그 위 한 칸.
     * 기준값 초과 횟수({@link #THRESHOLDS_S}, "넘게" = 초과)와 최대·최대 시작 시각은 정확히 센다.
     */
    static final class Hist {
        static final int BINS = 100 + 90 + 590 + 1;
        final long[] bins = new long[BINS];
        final long[] over = new long[THRESHOLDS_S.length];
        long count;
        long max = -1;
        Instant maxFrom;

        void add(long micros, Instant from) {
            bins[bin(micros)]++;
            count++;
            if (micros > max) {
                max = micros;
                maxFrom = from;
            }
            for (int i = 0; i < THRESHOLDS_S.length; i++) {
                if (micros > THRESHOLDS_S[i] * 1_000_000L) {
                    over[i]++;
                }
            }
        }

        static int bin(long micros) {
            if (micros < 1_000_000L) {
                return (int) (micros / 10_000L);
            }
            if (micros < 10_000_000L) {
                return 100 + (int) ((micros - 1_000_000L) / 100_000L);
            }
            if (micros < 600_000_000L) {
                return 190 + (int) ((micros - 10_000_000L) / 1_000_000L);
            }
            return BINS - 1;
        }

        /** 칸의 위 경계(그 칸 값은 이 값 미만). 마지막 칸은 경계가 없다. */
        static long upper(int bin) {
            if (bin < 100) {
                return (bin + 1) * 10_000L;
            }
            if (bin < 190) {
                return 1_000_000L + (bin - 100 + 1) * 100_000L;
            }
            if (bin < BINS - 1) {
                return 10_000_000L + (bin - 190 + 1) * 1_000_000L;
            }
            return Long.MAX_VALUE;
        }

        /** 가까운 순위 백분위의 위 경계. 최대보다 크면 최대. 값이 없으면 -1. */
        long percentile(double q) {
            if (count == 0) {
                return -1;
            }
            long rank = (long) Math.ceil(q * count);
            rank = Math.max(1, Math.min(count, rank));
            long seen = 0;
            for (int b = 0; b < BINS; b++) {
                seen += bins[b];
                if (seen >= rank) {
                    return Math.min(upper(b), max);
                }
            }
            return max;
        }

        long over(int seconds) {
            for (int i = 0; i < THRESHOLDS_S.length; i++) {
                if (THRESHOLDS_S[i] == seconds) {
                    return over[i];
                }
            }
            throw new IllegalArgumentException("세지 않는 기준값: " + seconds);
        }
    }

    static Instant fromMicros(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
    }

    static long toMicros(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000L);
    }

    // ---------------------------------------------------------------- 보고서

    record DayInfo(LocalDate date, Optional<RecordingMeta> meta, boolean truncated, long eventCount, long gapCount,
            int segments, Instant firstRx, Instant lastRx, ZoneId zone, Map<String, Long> sourceLines, long statusLines) {
        boolean complete() {
            return meta.map(RecordingMeta::complete).orElse(false) && !truncated;
        }
    }

    record Report(List<DayInfo> days, List<String> missing, Totals totals, SymbolNames names) {

        boolean allComplete() {
            return days.stream().allMatch(DayInfo::complete);
        }

        private ZoneId zone() {
            return days.isEmpty() ? ZoneId.of("Asia/Seoul") : days.get(0).zone();
        }

        String toMarkdown() {
            StringBuilder b = new StringBuilder();
            int cont = Window.CONTINUOUS.ordinal();
            b.append("# 시세 수신 간격 분포 (ADR-016 G0)\n\n");
            b.append("녹화(게이트웨이 수신 시각 `rx`)로 잰 종목 단위·소스 단위 이벤트 간격이다. 실전 엔진 입력 계약(ADR-016 2.6)의 ")
                    .append("\"조용함\"(T_quiet, 기본 `market-data.symbol-gap`)과 \"소스 끊김\"(정규장 접속 매매 3초) 기준의 근거로 쓴다. ")
                    .append("측정 방법과 한계는 맨 아래 절에 있다. 시각은 거래소 현지 시각이다.\n\n");

            b.append("## 입력 녹화\n\n");
            b.append("| 날짜 | 정상 종료 | 마지막 줄 잘림 | 줄 수 | 끊김 표시 | 상태 줄 | 녹화 구간 | 첫 수신 | 마지막 수신 | 소스별 줄 수 | eventsSha256 앞 12자 |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (DayInfo d : days) {
                b.append("| ").append(d.date()).append(" | ").append(d.meta().map(m -> m.complete() ? "예" : "아니오").orElse("메타 없음"))
                        .append(" | ").append(d.truncated() ? "예" : "아니오").append(" | ").append(d.eventCount())
                        .append(" | ").append(d.gapCount()).append(" | ").append(d.statusLines()).append(" | ")
                        .append(d.segments()).append(" | ")
                        .append(clock(d.firstRx(), d.zone())).append(" | ").append(clock(d.lastRx(), d.zone())).append(" | ")
                        .append(d.sourceLines()).append(" | ")
                        .append(d.meta().map(RecordingMeta::eventsSha256).filter(h -> h.length() >= 12).map(h -> h.substring(0, 12))
                                .orElse("-"))
                        .append(" |\n");
            }
            if (!missing.isEmpty()) {
                b.append("\n녹화 폴더가 없는 거래일: ").append(String.join(", ", missing)).append('\n');
            }
            if (!allComplete()) {
                b.append("\n정상 종료되지 않았거나 잘린 녹화일이 있다. 그날 값은 녹화가 끝난 곳까지만 들어갔다.\n");
            }
            if (days.stream().mapToLong(DayInfo::statusLines).sum() == 0) {
                b.append("\n**VI 분리 안 됨**: 녹화에 종목 상태(`STATUS`) 줄이 하나도 없다(지금 키움·한국투자 수신은 상태 줄을 만들지 않음). ")
                        .append("VI·정지 중 간격이 접속 매매 분포에 섞여 있으므로 긴 간격 일부는 VI일 수 있다.\n");
            }
            if (totals.backwards > 0) {
                b.append("\n수신 시각이 앞 줄보다 이른 줄: ").append(totals.backwards).append("건(간격 0으로 셈)\n");
            }

            b.append("\n## 소스 단위 (장애표 #1 \"수신 간격 > 3초\", ADR-016 2.6 소스 끊김)\n\n");
            b.append("한 소스가 보낸 모든 종목 이벤트 사이 간격. 접속 매매 행의 \">3s\"가 0이 아니면 감시 종목 수에서 3초 기준이 오탐을 낸다. ")
                    .append("단일가 행은 risk-ops PR #245 N3(단일가 구간 짧은 기준 가능 여부) 근거다.\n\n");
            b.append(statsHeader());
            totals.sources.forEach((src, st) -> {
                for (int w = 0; w < W; w++) {
                    b.append(statsRow(src, Window.values()[w], st, w));
                }
            });

            b.append("\n### 소스 단위 날짜별 (접속 매매)\n\n");
            b.append("| 날짜 | 소스 | 간격 수 | p99 | p99.9 | 최대 | 최대 시작 | >2s | >3s | >5s |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|\n");
            totals.sourceDaily.forEach((date, bySrc) -> bySrc.forEach((src, h) -> b.append("| ").append(date).append(" | ")
                    .append(src).append(" | ").append(h.count).append(" | ").append(le(h.percentile(0.99))).append(" | ")
                    .append(le(h.percentile(0.999))).append(" | ").append(dur(h.max)).append(" | ")
                    .append(at(h.maxFrom)).append(" | ").append(h.over(2)).append(" | ").append(h.over(3)).append(" | ")
                    .append(h.over(5)).append(" |\n")));

            b.append("\n## 종목 단위 전체 (T_quiet, 장애표 #15·RSK-08 `market-data.symbol-gap`)\n\n");
            b.append("모든 종목의 간격을 합친 분포. VI 중 시작한 간격은 \"VI 중\" 칸에만 넣었다. 간격 수로 가중한 값이라 거래가 많은 종목이 ")
                    .append("대부분을 차지하고 거래가 드문 종목은 거의 반영되지 않는다. T_quiet은 아래 후보별 표와 종목별 표로 판단한다.\n\n");
            b.append(statsHeader());
            for (int w = 0; w < W; w++) {
                b.append(statsRow("전체 종목", Window.values()[w], totals.allSymbols, w));
            }

            b.append("\n### T_quiet 후보별 조용함 발생 (접속 매매)\n\n");
            b.append("후보값 T에서 \"그 종목 마지막 이벤트가 T보다 오래됨\"이 몇 번 생기는가. 실전 엔진은 그때마다 그 종목을 보호 폴링으로 ")
                    .append("감시하고 신규 진입을 막는다(ADR-016 2.6). 종목·일 수는 ").append(totals.symbolDayCount).append(".\n\n");
            b.append("| T | 발생 횟수 | 종목·일당 평균 | 한 번이라도 넘은 종목·일 | 넘은 종목 수 | 가장 많은 종목 |\n");
            b.append("|---|---|---|---|---|---|\n");
            for (int i = 0; i < THRESHOLDS_S.length; i++) {
                int t = THRESHOLDS_S[i];
                if (t < 5) {
                    continue;
                }
                long total = totals.allSymbols.main[cont].over(t);
                long symbolsOver = totals.symbols.values().stream().filter(st -> st.main[cont].over(t) > 0).count();
                Optional<Map.Entry<Symbol, Stats>> worst = totals.symbols.entrySet().stream()
                        .filter(e -> e.getValue().main[cont].over(t) > 0)
                        .max((x, y) -> {
                            int c = Long.compare(x.getValue().main[cont].over(t), y.getValue().main[cont].over(t));
                            return c != 0 ? c : y.getKey().code().compareTo(x.getKey().code());
                        });
                b.append("| ").append(t).append("s | ").append(total).append(" | ")
                        .append(totals.symbolDayCount == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) total / totals.symbolDayCount))
                        .append(" | ").append(totals.symbolDaysOver[i]).append(" | ").append(symbolsOver).append(" | ")
                        .append(worst.map(e -> e.getKey().code() + " " + name(e.getKey()) + " (" + e.getValue().main[cont].over(t) + "회)")
                                .orElse("-"))
                        .append(" |\n");
            }

            b.append("\n### 단계 경계 (분포에 넣지 않은 것)\n\n");
            b.append("\"시작→첫\"은 단계 시작 → 첫 이벤트, \"경계 걸침\"은 앞 단계(또는 장전) 마지막 이벤트 → 이 단계 첫 이벤트다. ")
                    .append("엔진이 단계 경계에서 마지막 이벤트 시각을 지우지 않으면 \"경계 걸침\"이 엔진이 보는 나이다. ")
                    .append("시가 단일가 접수 마감이 임의로 늦춰져(최대 30초 `[가정]`) 접속 매매 행에서 매일 생길 수 있다.\n\n");
            b.append("| 대상 | 단계 | 종류 | 건수 | p99 | 최대 | 최대 시작 | >3s | >10s | >30s | >60s |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
            totals.sources.forEach((src, st) -> boundaryRows(b, src, st));
            boundaryRows(b, "전체 종목", totals.allSymbols);

            b.append("\n## 종목별 (접속 매매)\n\n");
            b.append("종목마다 T_quiet을 다르게 둘지 판단할 근거. \"개장→첫\"은 접속 매매 시작 → 그 종목 첫 이벤트 최대(분포에 넣지 않음).\n\n");
            b.append("| 종목 | 종목명 | 녹화일 | 간격 수 | p50 | p99 | p99.9 | 최대 | 최대 시작 |");
            for (int t : SYMBOL_TABLE_THRESHOLDS_S) {
                b.append(" >").append(t).append("s |");
            }
            b.append(" 단계 내내 없음 | 개장→첫 | VI 중 | 뺀 간격 |\n");
            b.append("|---|---|---|---|---|---|---|---|---|");
            for (int i = 0; i < SYMBOL_TABLE_THRESHOLDS_S.length; i++) {
                b.append("---|");
            }
            b.append("---|---|---|---|\n");
            totals.symbols.forEach((s, st) -> {
                Hist h = st.main[cont];
                b.append("| ").append(s.code()).append(" | ").append(name(s)).append(" | ")
                        .append(totals.symbolDays.getOrDefault(s, 0L)).append(" | ").append(h.count).append(" | ")
                        .append(le(h.percentile(0.5))).append(" | ").append(le(h.percentile(0.99))).append(" | ")
                        .append(le(h.percentile(0.999))).append(" | ").append(dur(h.max)).append(" | ").append(at(h.maxFrom))
                        .append(" |");
                for (int t : SYMBOL_TABLE_THRESHOLDS_S) {
                    b.append(' ').append(h.over(t)).append(" |");
                }
                b.append(' ').append(st.silent[cont]).append(" | ").append(dur(st.leading[cont].max)).append(" | ")
                        .append(st.vi[cont].count).append(" | ").append(st.excluded[cont]).append(" |\n");
            });

            b.append("\n## 측정 방법과 한계\n\n");
            b.append("- 간격은 게이트웨이 수신 시각(`rx`)으로 쟀다. 매매 단계는 `KrxMarketRules.sessionPhase`(시가 단일가 개장 30분 전~개장, ")
                    .append("접속 매매 개장~폐장 10분 전, 종가 단일가 폐장 10분 전~폐장)로 나눴고 단계 경계를 넘는 간격은 세지 않았다.\n");
            b.append("- 분포에 넣은 것: 단계 안 이어진 두 이벤트 사이, 마지막 이벤트 → 단계 끝(녹화가 단계 끝까지 이어진 날), ")
                    .append("단계 전에 이벤트가 있었는데 단계 내내 없으면 단계 길이 전체(\"단계 내내 없음\").\n");
            b.append("- 분포에서 뺀 것: 끊김 표시(`GAP`, 이유 무관)·녹화 재기동 경계를 지나는 간격(\"뺀 간격\"), 단계 시작 → 첫 이벤트(\"개장→첫\"·")
                    .append("\"시작→첫\")와 단계 경계를 지난 간격(\"경계 걸침\", 단계 경계 표), 종목이 VI·정지 상태일 때 시작한 간격(\"VI 중\", ")
                    .append("상태 줄이 있는 녹화만).\n");
            b.append("- 백분위는 히스토그램 칸의 위 경계(≤, 1초 미만 10ms·10초 미만 100ms·600초 미만 1초 단위)이고, 최대와 \">Ns\" 횟수는 정확한 값이다.\n");
            b.append("- 녹화는 게이트웨이가 받은 것 전부를 담는다고 본다 `[가정]`(녹화 큐 넘침은 `GAP(RECORDER_OVERFLOW)`로 남아 뺀 간격에 든다). ")
                    .append("엔진 전달은 관심 종목만 보내므로 소스 단위 간격은 엔진이 받는 종목이 적을수록 이 표보다 길어진다 `[추론]`. ")
                    .append("소스 3초 판단은 생존 줄의 소스별 `lastRx`(게이트웨이가 받은 모든 종목)로 하므로 이 표가 그 값에 해당한다(ADR-016 2.3 생존 줄).\n");
            b.append("- 끊긴 시간(`GAP`의 시작~수신, 재기동 앞 구간 마지막 줄~새 구간 첫 줄)이 겹친 단계와 끊긴 채 시작한 단계는 ")
                    .append("\"단계 내내 없음\"·\"시작→첫\"으로 세지 않는다. 녹화기 큐 넘침·재기동 표시(`RECORDER`)는 어느 소스 이벤트를 ")
                    .append("버렸는지 몰라 모든 소스 이어 보기를 끊는다.\n");
            b.append("- 그날 이벤트가 하나도 없는 감시 종목은 녹화에 나타나지 않아 표와 종목·일 수에서 빠진다(감시 목록은 읽지 않음). ")
                    .append("소스 전환(`SOURCE_SWITCH`)은 새 소스 이어 보기만 끊고, 옛 소스는 마지막 이벤트 → 단계 끝을 조용함으로 센다 `[추론]`.\n");
            b.append("- 끊김 뒤에는 VI 상태를 모르는 것으로 보고 지운다(다음 상태 줄까지).\n");
            b.append("- 감시 종목 구성(거래가 많은 종목 위주인지)에 따라 값이 달라진다. 실전 보호 대상(보유 종목)이 이 녹화 종목과 다르면 ")
                    .append("그 종목 녹화로 다시 잰다.\n");
            return b.toString();
        }

        private void boundaryRows(StringBuilder b, String target, Stats st) {
            for (int w = 0; w < W; w++) {
                for (int k = 0; k < 2; k++) {
                    Hist h = k == 0 ? st.leading[w] : st.cross[w];
                    b.append("| ").append(target).append(" | ").append(Window.values()[w].label).append(" | ")
                            .append(k == 0 ? "시작→첫" : "경계 걸침").append(" | ").append(h.count).append(" | ")
                            .append(le(h.percentile(0.99))).append(" | ").append(dur(h.max)).append(" | ").append(at(h.maxFrom))
                            .append(" | ").append(h.over(3)).append(" | ").append(h.over(10)).append(" | ").append(h.over(30))
                            .append(" | ").append(h.over(60)).append(" |\n");
                }
            }
        }

        private String name(Symbol s) {
            return names.name(s).map(n -> n.replace("|", "\\|")).orElse("–");
        }

        private static String statsHeader() {
            return "| 대상 | 단계 | 간격 수 | p50 | p99 | p99.9 | 최대 | 최대 시작 | >1s | >2s | >3s | >5s | >10s | >30s | >60s"
                    + " | 단계 내내 없음 | 시작→첫 최대 | VI 중 | 뺀 간격 |\n"
                    + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n";
        }

        private String statsRow(String target, Window w, Stats st, int i) {
            Hist h = st.main[i];
            return "| " + target + " | " + w.label + " | " + h.count + " | " + le(h.percentile(0.5)) + " | "
                    + le(h.percentile(0.99)) + " | " + le(h.percentile(0.999)) + " | " + dur(h.max) + " | " + at(h.maxFrom) + " | "
                    + h.over(1) + " | " + h.over(2) + " | " + h.over(3) + " | " + h.over(5) + " | " + h.over(10) + " | "
                    + h.over(30) + " | " + h.over(60) + " | " + st.silent[i] + " | " + dur(st.leading[i].max) + " | "
                    + st.vi[i].count + " | " + st.excluded[i] + " |\n";
        }

        private String at(Instant t) {
            if (t == null) {
                return "-";
            }
            var z = t.atZone(zone());
            return z.toLocalDate().toString().substring(5) + " " + z.toLocalTime().truncatedTo(ChronoUnit.SECONDS);
        }

        private static String clock(Instant t, ZoneId zone) {
            return t == null ? "-" : LocalTime.ofInstant(t, zone).truncatedTo(ChronoUnit.SECONDS).toString();
        }

        private static String le(long micros) {
            if (micros < 0) {
                return "-";
            }
            return "≤" + dur(micros);
        }

        static String dur(long micros) {
            if (micros < 0) {
                return "-";
            }
            if (micros < 10_000_000L) {
                return String.format(Locale.ROOT, "%.2fs", micros / 1e6);
            }
            if (micros < 600_000_000L) {
                return String.format(Locale.ROOT, "%.1fs", micros / 1e6);
            }
            long s = micros / 1_000_000L;
            return (s / 60) + "분 " + String.format(Locale.ROOT, "%02d", s % 60) + "초";
        }
    }
}
