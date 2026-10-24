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
    /** 스냅샷 수록 기준일(manifest coverage). 기본은 합성 데이터 끝날. */
    LocalDate lastTradingDate = TO;
    LocalDate sharesAsOf = TO;
    LocalDate financialsCollected = TO;
    /** 공시 입력(disclosure-count). 기본은 없음. */
    Disclosures disclosures;
    /** 실제 거래대금 입력(turnover, D-139). 기본은 없음(D-133 전 스냅샷). */
    Turnover turnover;

    /** 손 계산용 실제 거래대금 입력: 종목·날짜 → 원. 마지막 날짜는 따로 정한다(기본은 들어간 행 중 가장 늦은 날). */
    static final class Turnover implements TurnoverInput {
        final Map<Symbol, NavigableMap<LocalDate, Long>> rows = new TreeMap<>();
        LocalDate lastDate;
        String snapshotId = "krx-daily-test";

        Turnover put(String code, LocalDate date, long won) {
            rows.computeIfAbsent(sym(code), k -> new TreeMap<>()).put(date, won);
            lastDate = lastDate == null || date.isAfter(lastDate) ? date : lastDate;
            return this;
        }

        @Override
        public String snapshotId() {
            return snapshotId;
        }

        @Override
        public String sha256() {
            return "e".repeat(64);
        }

        @Override
        public LocalDate lastDate() {
            return lastDate;
        }

        @Override
        public OptionalLong turnover(Symbol symbol, LocalDate date) {
            NavigableMap<LocalDate, Long> m = rows.get(symbol);
            Long v = m == null ? null : m.get(date);
            return v == null ? OptionalLong.empty() : OptionalLong.of(v);
        }
    }

    /** 손 계산용 공시 입력: 유형별 수집 구간 하나, 종목·유형·접수일 목록(정정·코드 없는 행은 넣지 않은 것으로 본다). */
    static final class Disclosures implements DisclosureInput {
        final Map<String, LocalDate[]> ranges = new TreeMap<>();
        final List<Object[]> rows = new ArrayList<>();

        Disclosures covered(String type, LocalDate from, LocalDate to) {
            ranges.put(type, new LocalDate[] {from, to});
            return this;
        }

        Disclosures filed(String code, String type, LocalDate date) {
            rows.add(new Object[] {sym(code), type, date});
            return this;
        }

        @Override
        public String snapshotId() {
            return "dart-disclosures-test";
        }

        @Override
        public String sha256() {
            return "d".repeat(64);
        }

        @Override
        public SortedSet<String> types() {
            return new TreeSet<>(ranges.keySet());
        }

        @Override
        public boolean covers(String type, LocalDate from, LocalDate to) {
            LocalDate[] r = ranges.get(type);
            return r != null && !from.isBefore(r[0]) && !to.isAfter(r[1]);
        }

        @Override
        public LocalDate coveredThrough(String type, LocalDate date) {
            LocalDate[] r = ranges.get(type);
            return r != null && !date.isBefore(r[0]) && !date.isAfter(r[1]) ? r[1] : null;
        }

        @Override
        public int count(Symbol symbol, Set<String> types, LocalDate from, LocalDate to) {
            int n = 0;
            for (Object[] r : rows) {
                LocalDate d = (LocalDate) r[2];
                if (r[0].equals(symbol) && types.contains((String) r[1]) && !d.isBefore(from) && !d.isAfter(to)) {
                    n++;
                }
            }
            return n;
        }
    }

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

    /** 시가·고가·저가·종가가 다른 일봉(단타 지표 day-range·close-strength용). */
    FixtureData ohlc(String code, LocalDate date, String open, String high, String low, String close, long volume,
            String adj) {
        Symbol s = sym(code);
        bars.computeIfAbsent(s, k -> new TreeMap<>()).put(date, new DailyBar(s, date, new BigDecimal(open),
                new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume, new BigDecimal(adj)));
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
    public LocalDate barsLastTradingDate() {
        return lastTradingDate;
    }

    @Override
    public Optional<LocalDate> sharesAsOf() {
        return withShares ? Optional.of(sharesAsOf) : Optional.empty();
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
        return withFinancials ? Optional.of(new FinancialSnapshotInfo("dart-test", "b".repeat(64),
                financialsCollected == null ? null : financialsCollected + "T09:00:00Z", financialsCollected))
                : Optional.empty();
    }

    @Override
    public Optional<DisclosureInput> disclosures() {
        return Optional.ofNullable(disclosures);
    }

    @Override
    public Optional<TurnoverInput> turnover() {
        return Optional.ofNullable(turnover);
    }

    @Override
    public String calendarDigest() {
        return "c".repeat(64);
    }
}
