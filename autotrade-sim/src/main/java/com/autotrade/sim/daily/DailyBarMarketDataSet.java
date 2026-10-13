package com.autotrade.sim.daily;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.DailyBarSource;
import com.autotrade.sim.run.MarketDataSet;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 일봉 스냅샷(D-019, ADR-008)을 리플레이 실행기 입력({@link MarketDataSet})으로 바꾼 것: SIM-06 일봉 해상도(D-061).
 *
 * <p><b>일봉 경로 모델 {@value #PATH_MODEL}</b>: 종목·거래일마다 체결 이벤트 6개를 거래소 현지 시각에 고정해 둔다.
 * <pre>
 *   09:00 시가 · 09:10 시가 · 09:20 시가 · 11:00 고가 · 13:00 저가 · 15:00 종가
 * </pre>
 * <ul>
 *   <li>일봉 전략(스윙·중기)은 그날 첫 체결(09:00)에서 전일까지의 종가로 판단하고, 주문은 신호 이벤트 다음 이벤트부터
 *       체결되므로(ADR-004 2.2) 09:10 시가에 체결된다. 진입 허용 시간이 09:05에 시작하는 프로필은 09:10에 판단하고 09:20
 *       시가에 체결된다. 그래서 신호는 전일 종가까지(T), 체결은 다음 날 시가(SIM-06 "일봉(다음 날 시가)")다.</li>
 *   <li>시가를 세 번 두는 것은 체결 기회를 만들려는 것이지 가격 정보가 아니다(같은 값). 고가→저가 순서는 그날 종가와 무관하게
 *       고정해 경로가 미래 정보(종가 방향)를 담지 않게 한다. 저가에서 걸린 손절은 다음 이벤트(종가)에 체결된다 `[가정]`.</li>
 *   <li>거래량은 6칸에 나눠 둔다(시가 칸들은 거래량 ÷ 6, 나머지는 종가 칸, 거래량이 6 미만이면 칸마다 1). 장중 거래량을
 *       쓰는 단타 프로필은 이 데이터로 비교하지 않는다(호가·분 단위 정보가 없다).</li>
 *   <li>거래량 0인 날(거래정지, ADR-008 2.3)은 이벤트를 만들지 않는다. 그날은 체결할 수 없고 평가는 직전 체결가로 한다.</li>
 *   <li>체결은 호가가 없는 종목 규칙(다음 체결가 + 고정 슬리피지, {@code VirtualExchange})을 따른다.</li>
 * </ul>
 *
 * <p><b>가격 기준(D-061)</b>: 종목마다 이 데이터셋 첫 일봉을 기준으로 이은 가격이다.
 * {@code 가격(D) = 원가격(D) × adjFactor(D) ÷ adjFactor(첫 일봉)}. 첫 권리 변동(분할·병합·권리락) 전까지는 원가격 그대로이고,
 * 그 뒤에는 변동 전 단위로 이어져 보유 수량·이동평균·손절이 끊기지 않는다. 계수 비율은 첫 일봉과 D 사이 이벤트의 곱이라
 * D 뒤의 권리 변동(미래 정보)은 쓰지 않는다. 스냅샷 끝 기준 수정주가를 그대로 쓰면 아직 일어나지 않은 분할이 과거 가격대를
 * 바꿔 최소 가격 필터·수량 단위에 미래 정보가 들어간다(버린 대안). 원가격만 쓰면 분할 날 보유 평가가 급락한다(버린 대안).
 *
 * <p>이벤트는 거래일 → 시각 칸 → 종목 코드 순으로 정렬하고, sequence는 1부터 1씩 는다. 이벤트 목록은 미리 만들어 두지 않고
 * 읽을 때 만든다(메모리는 일봉 수에 비례). 불변이고 여러 Run이 동시에 읽어도 된다.
 */
public final class DailyBarMarketDataSet implements MarketDataSet {

    /** 일봉 → 이벤트 경로 모델 버전. 시각·순서·가격 기준을 바꾸면 올린다(같은 스냅샷이라도 결과가 달라진다). */
    public static final String PATH_MODEL = "DAILY-PATH-V1";

    /** 칸별 거래소 현지 시각. 정규장(2016-08 이전 15:00 폐장 포함) 안이다. */
    static final List<LocalTime> SLOTS = List.of(LocalTime.of(9, 0), LocalTime.of(9, 10), LocalTime.of(9, 20),
            LocalTime.of(11, 0), LocalTime.of(13, 0), LocalTime.of(15, 0));
    private static final int OPEN_SLOTS = 3;

    private final String version;
    private final Market market;
    private final List<Symbol> symbols;
    private final List<LocalDate> days;
    /** 거래일별 칸 시각. */
    private final Instant[][] slotTimes;
    /** 거래일 d의 행은 {@code rows[dayRowStart[d] .. dayRowStart[d+1])}, 종목 코드 순. */
    private final Row[] rows;
    private final int[] dayRowStart;
    /** 거래일 d의 첫 이벤트 위치. 길이는 거래일 수 + 1. */
    private final int[] dayEventStart;
    private final Map<Symbol, LocalDate> lastBarDates;
    private final int haltedBars;
    private final List<Symbol> adjustedSymbols;
    private final List<MarketEvent> events;

    private record Row(int symbol, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long qty,
            long closeQty) {
    }

    private DailyBarMarketDataSet(String version, Market market, List<Symbol> symbols, List<LocalDate> days,
            Instant[][] slotTimes, Row[] rows, int[] dayRowStart, int[] dayEventStart, Map<Symbol, LocalDate> lastBarDates,
            int haltedBars, List<Symbol> adjustedSymbols) {
        this.version = version;
        this.market = market;
        this.symbols = symbols;
        this.days = days;
        this.slotTimes = slotTimes;
        this.rows = rows;
        this.dayRowStart = dayRowStart;
        this.dayEventStart = dayEventStart;
        this.lastBarDates = lastBarDates;
        this.haltedBars = haltedBars;
        this.adjustedSymbols = adjustedSymbols;
        this.events = new Events();
    }

    /** 칸 시각은 합성이라 매매 단계를 보지 않는다(D-069, {@link MarketDataSet#exchangeTimed}). */
    @Override
    public boolean exchangeTimed() {
        return false;
    }

    /**
     * {@code [from, to]}에 그날 상장돼 있던 모든 종목(나중에 상장폐지된 종목 포함, 생존편향 제거)의 일봉으로 만든다.
     *
     * @param snapshotId 일봉 스냅샷 ID. 데이터셋 버전에 들어간다
     * @param zone 거래소 시간대
     * @throws IllegalArgumentException 범위가 뒤집혔거나 그 범위에 일봉이 하나도 없을 때
     */
    public static DailyBarMarketDataSet of(DailyBarSource source, String snapshotId, Market market, ZoneId zone,
            LocalDate from, LocalDate to) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(zone, "zone");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("범위가 뒤집혔습니다: " + from + " ~ " + to);
        }
        TreeSet<Symbol> universe = new TreeSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            universe.addAll(source.universe(market, d));
        }
        List<Symbol> symbols = List.copyOf(universe);
        SortedMap<LocalDate, List<Row>> byDay = new TreeMap<>();
        Map<Symbol, LocalDate> lastBarDates = new TreeMap<>();
        List<Symbol> adjusted = new ArrayList<>();
        int halted = 0;
        for (int i = 0; i < symbols.size(); i++) {
            Symbol s = symbols.get(i);
            List<DailyBar> bars = source.bars(s, from, to);
            if (bars.isEmpty()) {
                continue;
            }
            BigDecimal anchor = bars.get(0).adjFactor();
            boolean changed = false;
            for (DailyBar b : bars) {
                lastBarDates.put(s, b.date());
                if (b.volume() <= 0) {
                    halted++;
                    continue;
                }
                boolean same = b.adjFactor().compareTo(anchor) == 0;
                changed |= !same;
                long qty = Math.max(1, b.volume() / SLOTS.size());
                long closeQty = b.volume() < SLOTS.size() ? 1 : b.volume() - qty * (SLOTS.size() - 1);
                byDay.computeIfAbsent(b.date(), d -> new ArrayList<>()).add(new Row(i,
                        price(b.open(), b, anchor, same), price(b.high(), b, anchor, same),
                        price(b.low(), b, anchor, same), price(b.close(), b, anchor, same), qty, closeQty));
            }
            if (changed) {
                adjusted.add(s);
            }
        }
        if (byDay.isEmpty()) {
            throw new IllegalArgumentException("범위에 일봉이 없습니다: " + snapshotId + " " + from + " ~ " + to);
        }
        List<LocalDate> days = List.copyOf(byDay.keySet());
        Instant[][] slotTimes = new Instant[days.size()][];
        int[] dayRowStart = new int[days.size() + 1];
        int[] dayEventStart = new int[days.size() + 1];
        List<Row> all = new ArrayList<>();
        long eventCount = 0;
        for (int d = 0; d < days.size(); d++) {
            LocalDate date = days.get(d);
            slotTimes[d] = SLOTS.stream().map(t -> date.atTime(t).atZone(zone).toInstant()).toArray(Instant[]::new);
            List<Row> dayRows = byDay.get(date); // 종목 순서대로 넣었으므로 이미 코드 순
            dayRowStart[d] = all.size();
            dayEventStart[d] = (int) eventCount;
            all.addAll(dayRows);
            eventCount += (long) dayRows.size() * SLOTS.size();
            if (eventCount > Integer.MAX_VALUE - 1) {
                throw new IllegalArgumentException("이벤트가 너무 많습니다(기간이나 종목을 줄이세요): " + eventCount);
            }
        }
        dayRowStart[days.size()] = all.size();
        dayEventStart[days.size()] = (int) eventCount;
        String version = snapshotId + "#" + PATH_MODEL + "#" + from + "~" + to;
        return new DailyBarMarketDataSet(version, market, symbols, days, slotTimes, all.toArray(Row[]::new), dayRowStart,
                dayEventStart, Collections.unmodifiableMap(lastBarDates), halted, List.copyOf(adjusted));
    }

    /** 첫 일봉 기준으로 이은 가격. 계수가 같으면 원가격 그대로. */
    private static BigDecimal price(BigDecimal raw, DailyBar b, BigDecimal anchor, boolean same) {
        if (same) {
            return raw;
        }
        return raw.multiply(b.adjFactor()).divide(anchor, MathContext.DECIMAL64).stripTrailingZeros();
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Market market() {
        return market;
    }

    @Override
    public List<MarketEvent> events() {
        return events;
    }

    /** 이벤트가 있는 거래일(오름차순). */
    public List<LocalDate> tradingDays() {
        return days;
    }

    /** 범위 안에 그날 상장돼 있던 종목(코드 순). 일봉이 없는 종목도 있다. */
    public List<Symbol> symbols() {
        return symbols;
    }

    /** 종목별 범위 안 마지막 일봉 날짜(거래정지일 포함). */
    public Map<Symbol, LocalDate> lastBarDates() {
        return lastBarDates;
    }

    /** 거래량 0이라 이벤트를 만들지 않은 일봉 수. */
    public int haltedBars() {
        return haltedBars;
    }

    /** 범위 안에서 권리 변동이 있어 원가격과 다른 가격으로 이은 종목(코드 순). */
    public List<Symbol> adjustedSymbols() {
        return adjustedSymbols;
    }

    private MarketEvent event(int index) {
        int d = Arrays.binarySearch(dayEventStart, 0, days.size(), index);
        if (d < 0) {
            d = -d - 2;
        }
        int n = dayRowStart[d + 1] - dayRowStart[d];
        int local = index - dayEventStart[d];
        int slot = local / n;
        Row r = rows[dayRowStart[d] + local % n];
        BigDecimal p = slot < OPEN_SLOTS ? r.open() : switch (slot) {
            case 3 -> r.high();
            case 4 -> r.low();
            default -> r.close();
        };
        long qty = slot == SLOTS.size() - 1 ? r.closeQty() : r.qty();
        Instant t = slotTimes[d][slot];
        return new TradeTick(symbols.get(r.symbol()), index + 1L, t, t, p, qty);
    }

    private final class Events extends AbstractList<MarketEvent> implements RandomAccess {

        @Override
        public MarketEvent get(int index) {
            Objects.checkIndex(index, size());
            return event(index);
        }

        @Override
        public int size() {
            return dayEventStart[days.size()];
        }
    }
}
