package com.autotrade.selection.calc;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.financial.FinancialFiling;
import com.autotrade.marketdata.rules.InstrumentKind;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 손 계산 골든 테스트용 작은 합성 입력. 거래일은 2026-01-02 ~ 2026-06-30 평일에서 설날(2/16~18)과 3/2(대체공휴일 가정)를
 * 뺀 날이다. 실제 KRX 휴장일과 맞출 필요는 없다(테스트가 쓰는 계산 규칙만 본다).
 */
final class FixtureData implements SelectionData {

    static final LocalDate FROM = LocalDate.of(2026, 1, 2);
    static final LocalDate TO = LocalDate.of(2026, 6, 30);
    static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17),
            LocalDate.of(2026, 2, 18), LocalDate.of(2026, 3, 2));

    final TradingDays days;
    private final Map<Symbol, NavigableMap<LocalDate, DailyBar>> bars = new TreeMap<>();
    private final Map<Symbol, InstrumentPeriod> periods = new TreeMap<>();
    private final Map<Symbol, NavigableMap<LocalDate, Long>> shares = new TreeMap<>();
    private final Map<Symbol, List<FinancialFiling>> filings = new TreeMap<>();
    boolean withShares = true;
    boolean withFinancials = true;

    FixtureData() {
        List<LocalDate> d = new ArrayList<>();
        for (LocalDate x = FROM; !x.isAfter(TO); x = x.plusDays(1)) {
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY && !HOLIDAYS.contains(x)) {
                d.add(x);
            }
        }
        days = new TradingDays(d, FROM, TO);
    }

    static Symbol sym(String code) {
        return Symbol.krx(code);
    }

    FixtureData listed(String code, InstrumentKind kind, LocalDate from, LocalDate to) {
        Symbol s = sym(code);
        periods.put(s, new InstrumentPeriod(s, "종목" + code, kind, "KOSPI", from, Optional.ofNullable(to)));
        return this;
    }

    FixtureData stock(String code) {
        return listed(code, InstrumentKind.STOCK, FROM, null);
    }

    FixtureData bar(String code, LocalDate date, String close, long volume, String adj) {
        Symbol s = sym(code);
        BigDecimal c = new BigDecimal(close);
        bars.computeIfAbsent(s, k -> new TreeMap<>()).put(date, new DailyBar(s, date, c, c, c, c, volume, new BigDecimal(adj)));
        return this;
    }

    /** {@code end}(포함)에서 끝나는 n거래일에 같은 종가·거래량 일봉. */
    FixtureData flat(String code, LocalDate end, int n, String close, long volume) {
        for (LocalDate d : days.lastN(end, n)) {
            bar(code, d, close, volume, "1");
        }
        return this;
    }

    FixtureData removeBar(String code, LocalDate date) {
        bars.get(sym(code)).remove(date);
        return this;
    }

    FixtureData shares(String code, LocalDate from, long n) {
        shares.computeIfAbsent(sym(code), k -> new TreeMap<>()).put(from, n);
        return this;
    }

    /** 사업보고서 한 건. 금액은 원(매출, 영업이익, 자산, 부채, 자본). values가 null이면 값을 모르는 공시. */
    FixtureData annual(String code, int year, String receiptNo, LocalDate receipt, boolean correction, long[] values) {
        Symbol s = sym(code);
        boolean known = values != null;
        filings.computeIfAbsent(s, k -> new ArrayList<>()).add(new FinancialFiling(s, "C" + code, year, "11011", receiptNo,
                receipt, correction, known, known ? "CFS" : "", known ? bd(values[0]) : null, known ? bd(values[1]) : null,
                known ? bd(values[2]) : null, known ? bd(values[3]) : null, known ? bd(values[4]) : null));
        return this;
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    @Override
    public Market market() {
        return Market.KRX;
    }

    @Override
    public TradingDays tradingDays() {
        return days;
    }

    @Override
    public SortedSet<Symbol> universe(LocalDate date) {
        SortedSet<Symbol> out = new TreeSet<>();
        periods.forEach((s, p) -> {
            if (p.covers(date)) {
                out.add(s);
            }
        });
        return out;
    }

    @Override
    public Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date) {
        return Optional.ofNullable(periods.get(symbol)).filter(p -> p.covers(date));
    }

    @Override
    public List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to) {
        NavigableMap<LocalDate, DailyBar> m = bars.get(symbol);
        return m == null ? List.of() : List.copyOf(m.subMap(from, true, to, true).values());
    }

    @Override
    public OptionalLong listedShares(Symbol symbol, LocalDate date) {
        NavigableMap<LocalDate, Long> m = shares.get(symbol);
        if (!withShares || m == null || m.floorEntry(date) == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(m.floorEntry(date).getValue());
    }

    @Override
    public List<FinancialFiling> filings(Symbol symbol) {
        return withFinancials ? filings.getOrDefault(symbol, List.of()) : List.of();
    }

    @Override
    public String barsSnapshotId() {
        return "krx-daily-test";
    }

    @Override
    public String barsSha256() {
        return "a".repeat(64);
    }

    @Override
    public Optional<String> sharesSnapshotId() {
        return withShares ? Optional.of("krx-daily-test") : Optional.empty();
    }

    @Override
    public Optional<FinancialSnapshotInfo> financials() {
        return withFinancials ? Optional.of(new FinancialSnapshotInfo("dart-test", "b".repeat(64), "2026-06-30T09:00:00Z"))
                : Optional.empty();
    }

    @Override
    public String calendarDigest() {
        return "c".repeat(64);
    }
}
