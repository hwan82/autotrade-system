package com.autotrade.sim.walkforward;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.DailyBarSource;
import com.autotrade.marketdata.daily.SnapshotListedShares;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 벤치마크: 같은 일봉 스냅샷에서 계산한 시가총액 가중 지수와 동일가중 지수(참고). 4단계 계획 3장 3번, D-071 5번.
 *
 * <p><b>계산</b>: 거래일 d의 일수익률 {@code R_d = Σ w_i · r_i,d}, 가중치 {@code w_i = 전일 원종가 × 전일(장 마감) 상장주식수}를
 * 전체 합으로 나눈 값, {@code r_i,d = (종가_d × adjFactor_d) / (종가_{d-1} × adjFactor_{d-1}) − 1}(권리 변동으로 이은 수정
 * 종가의 비율). 지수 수준은 {@code L_d = L_{d-1} · (1 + R_d)}로 잇는다. 동일가중은 {@code r_i,d}의 단순 평균이다.
 * 종목은 전일과 당일 일봉이 모두 있는 종목(상장폐지된 종목은 마지막 일봉일까지 포함)이다. 거래정지일(거래량 0) 일봉도 수익률
 * 0으로 들어간다.
 *
 * <p><b>미래 정보</b>: 가중치는 전일 종가·전일 상장주식수만 쓴다({@link SnapshotListedShares#asOf}는 그날 장 마감 시점 값).
 * 시가총액 가중 지수에서 상장주식수를 모르는 종목은 그날 가중치에서 빠지고(건수를 {@link #capExcluded()}에 센다) 동일가중에는 남는다.
 *
 * <p><b>한계</b>(결과 파일에 같이 적는다): 가격 지수라 배당이 없다(계좌도 같다). 비용이 없다. 상장폐지 종목은 마지막 일봉 이후의 손실
 * (정리매매·청산가치)이 일봉에 없어 반영되지 않는다. 그래서 지수가 실제보다 낙관적일 수 있고 계좌도 같은 방향으로 틀린다.
 * 우선주·ETF 포함 여부는 일봉 스냅샷의 유니버스를 따른다.
 */
public final class MarketIndex {

    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);

    private final NavigableMap<LocalDate, BigDecimal> capLevel;
    private final NavigableMap<LocalDate, BigDecimal> equalLevel;
    private final int constituentDays;
    private final long constituentSum;
    private final long capExcluded;

    private MarketIndex(NavigableMap<LocalDate, BigDecimal> cap, NavigableMap<LocalDate, BigDecimal> equal,
            int constituentDays, long constituentSum, long capExcluded) {
        this.capLevel = cap;
        this.equalLevel = equal;
        this.constituentDays = constituentDays;
        this.constituentSum = constituentSum;
        this.capExcluded = capExcluded;
    }

    /**
     * @param from 지수를 잇기 시작하는 날(이 날 이후 첫 일봉일이 수준 1의 기준일)
     * @param to 마지막 날
     * @param shares 상장주식수. null이면 시가총액 가중을 계산할 수 없다
     */
    public static MarketIndex build(DailyBarSource bars, SnapshotListedShares shares, Market market, LocalDate from,
            LocalDate to) {
        Objects.requireNonNull(bars, "bars");
        if (shares == null) {
            throw new IllegalStateException("시가총액 가중 지수에는 상장주식수(listed_shares.csv)가 필요합니다: " + bars.snapshotId());
        }
        return build(bars, shares::asOf, market, from, to);
    }

    /** 날짜 D 장 마감 시점 상장주식수 조회. */
    @FunctionalInterface
    public interface SharesAsOf {
        OptionalLong asOf(Symbol symbol, LocalDate date);
    }

    public static MarketIndex build(DailyBarSource bars, SharesAsOf shares, Market market, LocalDate from, LocalDate to) {
        TreeSet<Symbol> symbols = new TreeSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            symbols.addAll(bars.universe(market, d));
        }
        List<List<DailyBar>> all = new ArrayList<>();
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (Symbol s : symbols) {
            List<DailyBar> list = bars.bars(s, from, to);
            if (!list.isEmpty()) {
                all.add(list);
                list.forEach(b -> dates.add(b.date()));
            }
        }
        if (dates.size() < 2) {
            throw new IllegalArgumentException("지수를 잇기에 일봉 날짜가 모자랍니다: " + from + " ~ " + to);
        }
        // 직전 거래일 표: 전체 종목 중 하나라도 일봉이 있는 날을 거래일로 본다(SnapshotTradingDays와 같은 가정)
        Map<LocalDate, LocalDate> previous = new TreeMap<>();
        LocalDate prev = null;
        for (LocalDate d : dates) {
            if (prev != null) {
                previous.put(d, prev);
            }
            prev = d;
        }
        Map<LocalDate, BigDecimal[]> acc = new TreeMap<>(); // sumW, sumWR, count, sumR
        long excluded = 0;
        for (List<DailyBar> list : all) {
            DailyBar before = null;
            for (DailyBar b : list) {
                if (before != null && before.date().equals(previous.get(b.date()))
                        && before.close().signum() > 0 && b.close().signum() > 0) {
                    BigDecimal r = b.close().multiply(b.adjFactor()).divide(before.close().multiply(before.adjFactor()), MC)
                            .subtract(BigDecimal.ONE);
                    BigDecimal[] a = acc.computeIfAbsent(b.date(),
                            k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                    OptionalLong n = shares.asOf(before.symbol(), before.date());
                    if (n.isPresent()) {
                        BigDecimal w = before.close().multiply(BigDecimal.valueOf(n.getAsLong()));
                        a[0] = a[0].add(w);
                        a[1] = a[1].add(w.multiply(r));
                    } else {
                        excluded++;
                    }
                    a[2] = a[2].add(BigDecimal.ONE);
                    a[3] = a[3].add(r);
                }
                before = b;
            }
        }
        NavigableMap<LocalDate, BigDecimal> cap = new TreeMap<>();
        NavigableMap<LocalDate, BigDecimal> equal = new TreeMap<>();
        LocalDate first = dates.first();
        cap.put(first, BigDecimal.ONE);
        equal.put(first, BigDecimal.ONE);
        long sum = 0;
        int days = 0;
        for (LocalDate d : dates.tailSet(first, false)) {
            BigDecimal[] a = acc.get(d);
            BigDecimal rc = BigDecimal.ZERO;
            BigDecimal re = BigDecimal.ZERO;
            if (a != null) {
                rc = a[0].signum() == 0 ? BigDecimal.ZERO : a[1].divide(a[0], MC);
                re = a[2].signum() == 0 ? BigDecimal.ZERO : a[3].divide(a[2], MC);
                sum += a[2].longValueExact();
                days++;
            }
            cap.put(d, cap.get(previous.get(d)).multiply(BigDecimal.ONE.add(rc), MC));
            equal.put(d, equal.get(previous.get(d)).multiply(BigDecimal.ONE.add(re), MC));
        }
        return new MarketIndex(cap, equal, days, sum, excluded);
    }

    /** 시가총액 가중 지수의 {@code [from, to]} 수익률: 마지막 거래일(≤ to) 수준 / {@code from} 직전 거래일 수준 − 1. */
    public BigDecimal capReturn(LocalDate from, LocalDate to) {
        return ret(capLevel, from, to);
    }

    public BigDecimal equalReturn(LocalDate from, LocalDate to) {
        return ret(equalLevel, from, to);
    }

    /** 구간을 이은 수익률: Π(1 + r) − 1. */
    public static BigDecimal chain(List<BigDecimal> returns) {
        BigDecimal g = BigDecimal.ONE;
        for (BigDecimal r : returns) {
            g = g.multiply(BigDecimal.ONE.add(r), MC);
        }
        return g.subtract(BigDecimal.ONE);
    }

    private static BigDecimal ret(NavigableMap<LocalDate, BigDecimal> level, LocalDate from, LocalDate to) {
        Map.Entry<LocalDate, BigDecimal> base = level.lowerEntry(from);
        Map.Entry<LocalDate, BigDecimal> end = level.floorEntry(to);
        if (base == null || end == null || !end.getKey().isAfter(base.getKey())) {
            throw new IllegalArgumentException("지수 수준을 찾을 수 없습니다(기간 밖이거나 거래일 없음): " + from + " ~ " + to);
        }
        return end.getValue().divide(base.getValue(), MC).subtract(BigDecimal.ONE);
    }

    /** 일수익률 계산에 쓴 종목 수의 거래일 평균. */
    public BigDecimal averageConstituents() {
        return constituentDays == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(constituentSum).divide(BigDecimal.valueOf(constituentDays), 1, RoundingMode.HALF_UP);
    }

    /** 상장주식수를 몰라 시가총액 가중에서 뺀 (종목, 거래일) 수. */
    public long capExcluded() {
        return capExcluded;
    }
}
