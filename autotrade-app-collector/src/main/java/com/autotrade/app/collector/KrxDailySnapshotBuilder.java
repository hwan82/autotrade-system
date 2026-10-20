package com.autotrade.app.collector;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.rules.InstrumentKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 일별매매정보를 일봉 스냅샷 행으로 바꾼다(MD-07, ADR-008 2.3). 같은 입력이면 같은 행·같은 순서(날짜, 종목 코드 순).
 *
 * <p>두 번 훑는다. 첫 번째에 종목별 수정 이벤트·상장주식수 변화·이력 구간을 모으고, 두 번째에 수정 계수를 붙여 일봉을 쓴다.
 * 전 기간 일봉을 메모리에 들고 있지 않는다.
 *
 * <ul>
 *   <li><b>수정 계수</b>: 기준가 = 종가 − 전일 대비. 기준가가 전일 종가와 다르면 그날 권리 변동(분할·병합·무상증자·유상증자
 *       권리락 등)이 있었던 것으로 보고 이벤트 비율 f = 기준가 ÷ 전일 종가(유효숫자 16자리)를 둔다. 날짜 D의 누적 계수는
 *       D 뒤 이벤트 비율의 곱이고 마지막 날은 1이다. 증권사 수정주가와 같은 방식이다 `[추론]`(표본 대조는 runbook).</li>
 *   <li><b>거래정지일</b>: 시가·고가·저가가 없거나 0이면 그 가격을 종가로 둔다. 거래량 0이면 거래정지일(품질 검사
 *       ZERO_VOLUME 경고), 거래량이 있으면 원자료 이상으로 따로 센다. 종가도 없으면 그날 일봉을 쓰지 않는다.</li>
 *   <li><b>이력 구간</b>: (시장, 소속부, 종목명)이 바뀌면 새 구간. 수집 마지막 날까지 나오는 종목은 끝 날짜가 비고, 그 전에
 *       사라진 종목은 마지막으로 나온 날이 끝 날짜(상장폐지 포함, 생존편향 제거).</li>
 *   <li><b>상장주식수</b>: 값이 바뀐 날(첫 날 포함)만 행으로 남긴다.</li>
 *   <li><b>실제 거래대금</b>(D-133): 받는 쪽을 주면 일봉을 쓴 행 중 {@code ACC_TRDVAL}이 있는 행만 {@value #TURNOVER_FILE}에 쓴다.
 *       일봉 행은 받는 쪽이 있든 없든 같다.</li>
 * </ul>
 */
public final class KrxDailySnapshotBuilder {

    static final MathContext MC = MathContext.DECIMAL64;

    /** 행을 받는 쪽(스냅샷 CSV 또는 테스트 목록). */
    @FunctionalInterface
    public interface RowSink {
        void write(List<String> row) throws IOException;
    }

    /**
     * 수집 요약(품질 리포트 "수집 요약" 칸).
     *
     * @param emptyWeekdays 모든 시장이 빈 목록인 평일(휴장일이거나, 거래일이 휴장일로 캐시된 것)
     * @param boardGaps 다른 시장엔 자료가 있는데 한 시장만 빈 날(그 시장이 앞뒤 날짜엔 나올 때). 다음 거래일에 가짜 수정 이벤트를 만든다
     * @param massAdjustmentDays 수정 이벤트가 {@link #MASS_ADJUSTMENT_MIN}개 종목 이상인 날
     * @param eventsAfterEmpty 빈 평일 → 그 뒤 첫 자료 있는 날의 수정 이벤트 종목 수(뒤에 자료 있는 날이 없으면 없음). 실제 휴장일이면
     *     0이거나 권리락 몇 건이고, 거래일이 빈 날로 캐시됐으면 그 시장 종목 대부분이다(PR #141 strategy-researcher R1)
     */
    public record Stats(long rows, long bars, long haltedBars, long filledMissingPrice, long droppedNoClose,
            long adjustmentEvents, long missingChange, long reappeared, LocalDate firstDate, LocalDate lastTradingDate,
            List<LocalDate> emptyWeekdays, List<BoardGap> boardGaps, List<AdjustmentDay> massAdjustmentDays,
            Map<LocalDate, Integer> eventsAfterEmpty, TurnoverStats turnover) {

        public Stats {
            eventsAfterEmpty = Map.copyOf(eventsAfterEmpty);
            emptyWeekdays = List.copyOf(emptyWeekdays);
            boardGaps = List.copyOf(boardGaps);
            massAdjustmentDays = List.copyOf(massAdjustmentDays);
            turnover = turnover == null ? TurnoverStats.NOT_WRITTEN : turnover;
        }

        /** 거래대금 파일 없이(옛 형식, 합성 통계용). */
        public Stats(long rows, long bars, long haltedBars, long filledMissingPrice, long droppedNoClose, long adjustmentEvents,
                long missingChange, long reappeared, LocalDate firstDate, LocalDate lastTradingDate,
                List<LocalDate> emptyWeekdays, List<BoardGap> boardGaps, List<AdjustmentDay> massAdjustmentDays,
                Map<LocalDate, Integer> eventsAfterEmpty) {
            this(rows, bars, haltedBars, filledMissingPrice, droppedNoClose, adjustmentEvents, missingChange, reappeared,
                    firstDate, lastTradingDate, emptyWeekdays, boardGaps, massAdjustmentDays, eventsAfterEmpty, null);
        }

        /** 공백 목록 없이(합성 통계용). */
        public Stats(long rows, long bars, long haltedBars, long filledMissingPrice, long droppedNoClose, long adjustmentEvents,
                long missingChange, long reappeared, LocalDate firstDate, LocalDate lastTradingDate) {
            this(rows, bars, haltedBars, filledMissingPrice, droppedNoClose, adjustmentEvents, missingChange, reappeared,
                    firstDate, lastTradingDate, List.of(), List.of(), List.of(), Map.of(), null);
        }
    }

    /**
     * 실제 거래대금 파일 {@value #TURNOVER_FILE} 요약(D-133).
     *
     * @param written 이번 스냅샷에 거래대금 파일을 썼는지({@code --without-turnover}면 아님)
     * @param rows 거래대금 파일 행(일봉 중 {@code ACC_TRDVAL}이 있던 행)
     * @param missing 일봉은 썼는데 응답에 {@code ACC_TRDVAL}이 없거나 빈 행(거래대금 파일에 넣지 않음)
     * @param outsideRange 거래대금이 저가 × 거래량 ~ 고가 × 거래량(원가격) 밖인 행. 시간외 거래가 정규장 범위 밖 가격으로
     *     체결되면 생길 수 있어 경고로만 센다 `[추론]`
     * @param outsideSamples 그런 행 앞 몇 개({@value #OUTSIDE_SAMPLES}개까지, "날짜 코드" 순)
     */
    public record TurnoverStats(boolean written, long rows, long missing, long outsideRange, List<String> outsideSamples) {

        static final TurnoverStats NOT_WRITTEN = new TurnoverStats(false, 0, 0, 0, List.of());

        public TurnoverStats {
            outsideSamples = List.copyOf(outsideSamples);
        }
    }

    /**
     * 실제 거래대금 파일(D-133, ADR-012 후속 F1). 일봉 스냅샷의 선택 파일이다. 읽는 쪽은 다음 PR에서
     * 선정 모듈에 만들 예정이다(D-133 6번). 수집기는 선정 모듈에 의존하지 않으므로 그때 이름·헤더를 양쪽에 같게 두고 대조 테스트를 넣는다.
     * 값은 KRX {@code ACC_TRDVAL} 그대로(원, 원가격 기준이라 수정 계수를 곱하지 않는다). 일봉을 쓴 행 중 값이 있는 행만 둔다.
     */
    public static final String TURNOVER_FILE = "daily_turnover.csv";

    public static final List<String> TURNOVER_HEADER = List.of("market", "code", "date", "turnover");

    static final int OUTSIDE_SAMPLES = 20;

    /** 한 시장만 빈 날. */
    public record BoardGap(LocalDate date, KrxBoard board) {}

    /**
     * 수정 이벤트가 몰린 날.
     *
     * @param symbols 그날 나온 종목 수
     * @param gapsBefore 직전 자료 있는 날(그날이 시장 공백이면 그날 포함)부터 이 날 전까지의 빈 평일과 시장 공백 날짜 전부
     *     (오름차순). 실제 휴장일은 다음 거래일에 이벤트를 만들지 않으므로, 이 중 휴장일이 아닌 날이 원인이다. 가장 가까운 하나만
     *     적으면 "가짜 빈 거래일 → 실제 휴장일 → 이 날" 순서에서 원인이 빠진다(PR #141 strategy-researcher M1)
     */
    public record AdjustmentDay(LocalDate date, int events, int symbols, List<LocalDate> gapsBefore) {

        public AdjustmentDay {
            gapsBefore = List.copyOf(gapsBefore);
        }
    }

    /**
     * 같은 날 이 수 이상 종목에 수정 이벤트가 나오면 경고한다 `[가정]`. 실제 권리락은 하루 수 건~수십 건이고, 시장 하나가 하루
     * 빠지면 다음 날 그 시장 종목 거의 전부(코스닥 약 1,700)에 이벤트가 생긴다(PR #137 strategy-researcher R1 (c)).
     */
    static final int MASS_ADJUSTMENT_MIN = 30;

    /** 코드가 이 거래일 수보다 오래 비었다가 다시 나오면 다른 회사로 보고 전일 종가를 잇지 않는다(코드 재사용, `[가정]`). */
    static final int REAPPEAR_GAP_DAYS = 20;

    private KrxDailySnapshotBuilder() {}

    /** 거래대금 파일 없이(옛 형식). */
    public static Stats build(KrxDaySource source, RowSink bars, RowSink history, RowSink shares) throws IOException {
        return build(source, bars, history, shares, null);
    }

    /**
     * @param turnover 실제 거래대금 행을 받는 쪽({@value #TURNOVER_FILE}). {@code null}이면 쓰지 않는다(옛 형식, 옛 스냅샷 ID 재현용)
     */
    public static Stats build(KrxDaySource source, RowSink bars, RowSink history, RowSink shares, RowSink turnover)
            throws IOException {
        Map<String, SymbolState> states = new TreeMap<>();
        long rows = 0;
        long missingChange = 0;
        long reappeared = 0;
        int dayIndex = -1;
        LocalDate first = null;
        LocalDate last = null;
        List<LocalDate> emptyWeekdays = new ArrayList<>();
        List<LocalDate> tradingDates = new ArrayList<>();
        Map<KrxBoard, java.util.NavigableSet<Integer>> boardDays = new java.util.EnumMap<>(KrxBoard.class);
        TreeMap<LocalDate, int[]> dayEvents = new TreeMap<>(); // 자료 있는 날짜 → {이벤트 수, 종목 수}
        // 1차: 이벤트·주식수·구간
        for (LocalDate d : source.dates()) {
            List<KrxDailyRow> day = sorted(source.rows(d));
            if (day.isEmpty()) {
                emptyWeekdays.add(d); // 휴장일(또는 휴장일로 캐시된 거래일)
                continue;
            }
            first = first == null ? d : first;
            last = d;
            dayIndex++;
            tradingDates.add(d);
            int eventsToday = 0;
            int idx = dayIndex;
            day.stream().map(KrxDailyRow::board).distinct()
                    .forEach(b -> boardDays.computeIfAbsent(b, x -> new java.util.TreeSet<>()).add(idx));
            for (KrxDailyRow r : day) {
                rows++;
                SymbolState s = states.computeIfAbsent(r.code(), c -> new SymbolState());
                if (s.lastDayIndex >= 0 && dayIndex - s.lastDayIndex > REAPPEAR_GAP_DAYS) {
                    s.lastClose = null; // 옛 회사 마지막 종가로 새 회사의 수정 비율을 잡지 않는다
                    s.segmentStarts.add(d); // 새 회사 이벤트를 옛 회사 일봉에 곱하지 않는다(QA PR #39 R5)
                    reappeared++;
                }
                s.lastDayIndex = dayIndex;
                BigDecimal close = positive(r.close());
                if (close != null && s.lastClose != null) {
                    if (r.change() == null) {
                        missingChange++;
                    } else {
                        BigDecimal base = close.subtract(r.change());
                        if (base.signum() > 0 && base.compareTo(s.lastClose) != 0) {
                            s.events.put(d, base.divide(s.lastClose, MC));
                            eventsToday++;
                        }
                    }
                }
                if (close != null) {
                    s.lastClose = close;
                }
                if (r.listedShares() > 0 && r.listedShares() != s.lastShares) {
                    s.lastShares = r.listedShares();
                    shares.write(List.of(Market.KRX.name(), r.code(), d.toString(), Long.toString(r.listedShares())));
                }
                String board = r.section().isEmpty() ? r.board().name() : r.board().name() + "/" + r.section();
                if (s.open == null || !s.open.board.equals(board) || !s.open.name.equals(r.name())) {
                    if (s.open != null) {
                        s.closed.add(s.open);
                    }
                    s.open = new Period(board, r.name(), d);
                }
                s.open.lastSeen = d;
            }
            dayEvents.put(d, new int[] {eventsToday, day.size()});
        }
        List<BoardGap> boardGaps = new ArrayList<>();
        java.util.Set<LocalDate> gapDays = new java.util.HashSet<>(emptyWeekdays);
        for (Map.Entry<KrxBoard, java.util.NavigableSet<Integer>> e : boardDays.entrySet()) {
            java.util.NavigableSet<Integer> seen = e.getValue();
            for (int i = seen.first(); i <= seen.last(); i++) {
                if (!seen.contains(i)) {
                    boardGaps.add(new BoardGap(tradingDates.get(i), e.getKey()));
                    gapDays.add(tradingDates.get(i));
                }
            }
        }
        boardGaps.sort(Comparator.comparing(BoardGap::date).thenComparing(BoardGap::board));
        List<AdjustmentDay> massDays = new ArrayList<>();
        List<LocalDate> allDates = source.dates();
        for (int i = 0; i < allDates.size(); i++) {
            int[] c = dayEvents.get(allDates.get(i));
            if (c != null && c[0] >= MASS_ADJUSTMENT_MIN) {
                massDays.add(new AdjustmentDay(allDates.get(i), c[0], c[1], gapsBefore(allDates, i, gapDays, dayEvents)));
            }
        }
        Map<LocalDate, Integer> eventsAfterEmpty = new TreeMap<>();
        for (LocalDate e : emptyWeekdays) {
            Map.Entry<LocalDate, int[]> next = dayEvents.higherEntry(e);
            if (next != null) {
                eventsAfterEmpty.put(e, next.getValue()[0]);
            }
        }
        // 구간 쓰기(종목 코드, 시작일 순)
        for (Map.Entry<String, SymbolState> e : states.entrySet()) {
            SymbolState s = e.getValue();
            if (s.open != null) {
                s.closed.add(s.open);
            }
            for (Period p : s.closed) {
                String to = Objects.equals(p.lastSeen, last) && p == s.open ? "" : p.lastSeen.toString();
                history.write(List.of(Market.KRX.name(), e.getKey(), p.name, InstrumentKind.STOCK.name(), p.board,
                        p.from.toString(), to));
            }
            s.cumulative = new TreeMap<>();
            LocalDate segStart = LocalDate.MIN;
            for (LocalDate next : s.segmentStarts) {
                s.cumulative.put(segStart, cumulative(s.events.subMap(segStart, true, next, false)));
                segStart = next;
            }
            s.cumulative.put(segStart, cumulative(s.events.tailMap(segStart, true)));
        }
        // 2차: 일봉
        long barCount = 0;
        long halted = 0;
        long filled = 0;
        long dropped = 0;
        long events = 0;
        long turnoverRows = 0;
        long turnoverMissing = 0;
        long outsideRange = 0;
        List<String> outsideSamples = new ArrayList<>();
        for (SymbolState s : states.values()) {
            events += s.events.size();
        }
        for (LocalDate d : source.dates()) {
            for (KrxDailyRow r : sorted(source.rows(d))) {
                BigDecimal close = positive(r.close());
                if (close == null) {
                    dropped++;
                    continue;
                }
                BigDecimal open = positive(r.open());
                BigDecimal high = positive(r.high());
                BigDecimal low = positive(r.low());
                if (open == null || high == null || low == null) {
                    // 거래정지일이면 정상, 거래량이 있는데 가격이 비면 원자료 이상이라 따로 센다
                    if (r.volume() == 0) {
                        halted++;
                    } else {
                        filled++;
                    }
                    open = open == null ? close : open;
                    high = high == null ? close : high;
                    low = low == null ? close : low;
                }
                Map.Entry<LocalDate, BigDecimal> f = states.get(r.code()).cumulative.floorEntry(d).getValue().higherEntry(d);
                BigDecimal factor = f == null ? BigDecimal.ONE : f.getValue();
                bars.write(List.of(Market.KRX.name(), r.code(), d.toString(), plain(open), plain(high), plain(low),
                        plain(close), Long.toString(r.volume()), plain(factor)));
                barCount++;
                if (turnover != null) {
                    if (r.turnover() == null) {
                        turnoverMissing++;
                    } else {
                        turnover.write(List.of(Market.KRX.name(), r.code(), d.toString(), Long.toString(r.turnover())));
                        turnoverRows++;
                        BigDecimal value = BigDecimal.valueOf(r.turnover());
                        BigDecimal volume = BigDecimal.valueOf(r.volume());
                        if (value.compareTo(low.multiply(volume)) < 0 || value.compareTo(high.multiply(volume)) > 0) {
                            if (outsideSamples.size() < OUTSIDE_SAMPLES) {
                                outsideSamples.add(d + " " + r.code());
                            }
                            outsideRange++;
                        }
                    }
                }
            }
        }
        TurnoverStats t = turnover == null ? TurnoverStats.NOT_WRITTEN
                : new TurnoverStats(true, turnoverRows, turnoverMissing, outsideRange, outsideSamples);
        return new Stats(rows, barCount, halted, filled, dropped, events, missingChange, reappeared, first, last,
                emptyWeekdays, boardGaps, massDays, eventsAfterEmpty, t);
    }

    /** 직전 자료 있는 날까지 거슬러 가며 만나는 빈 평일·시장 공백 날짜 전부(오름차순). 직전 자료 있는 날이 시장 공백이면 포함한다. */
    private static List<LocalDate> gapsBefore(List<LocalDate> dates, int i, java.util.Set<LocalDate> gapDays,
            Map<LocalDate, int[]> dayEvents) {
        List<LocalDate> out = new ArrayList<>();
        for (int j = i - 1; j >= 0; j--) {
            LocalDate d = dates.get(j);
            if (gapDays.contains(d)) {
                out.add(0, d);
            }
            if (dayEvents.containsKey(d)) {
                break; // 자료 있는 직전 거래일
            }
        }
        return out;
    }

    /** 이벤트 날짜 E → E 이후(E 포함) 이벤트 비율의 곱. 날짜 D의 계수는 D보다 뒤 첫 이벤트 날짜의 값이다. */
    static NavigableMap<LocalDate, BigDecimal> cumulative(NavigableMap<LocalDate, BigDecimal> events) {
        NavigableMap<LocalDate, BigDecimal> out = new TreeMap<>();
        BigDecimal product = BigDecimal.ONE;
        for (Map.Entry<LocalDate, BigDecimal> e : events.descendingMap().entrySet()) {
            product = product.multiply(e.getValue(), MC);
            out.put(e.getKey(), product);
        }
        return out;
    }

    private static List<KrxDailyRow> sorted(List<KrxDailyRow> rows) {
        List<KrxDailyRow> out = new ArrayList<>(rows);
        out.sort(Comparator.comparing(KrxDailyRow::code).thenComparing(KrxDailyRow::board));
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).code().equals(out.get(i - 1).code())) {
                // 같은 날 두 시장에 같은 코드가 나오면 어느 쪽이 맞는지 정할 근거가 없다
                throw new IllegalStateException("같은 날 같은 종목이 두 번 나왔습니다: " + out.get(i).code() + " " + out.get(i).date());
            }
        }
        return out;
    }

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static final class SymbolState {
        BigDecimal lastClose;
        int lastDayIndex = -1;
        long lastShares = -1;
        Period open;
        final List<Period> closed = new ArrayList<>();
        final NavigableMap<LocalDate, BigDecimal> events = new TreeMap<>();
        final java.util.NavigableSet<LocalDate> segmentStarts = new java.util.TreeSet<>();
        /** 구간 시작일(첫 구간은 {@link LocalDate#MIN}) → 그 구간 이벤트만의 누적 계수. */
        NavigableMap<LocalDate, NavigableMap<LocalDate, BigDecimal>> cumulative = new TreeMap<>();
    }

    private static final class Period {
        final String board;
        final String name;
        final LocalDate from;
        LocalDate lastSeen;

        Period(String board, String name, LocalDate from) {
            this.board = board;
            this.name = name;
            this.from = from;
        }
    }
}
