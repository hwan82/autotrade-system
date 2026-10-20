package com.autotrade.sim.edge;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.selection.calc.SnapshotDisclosures;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * 공시 선정의 생존편향 확인(D-125 2.4, PR #196 strategy-researcher R3·n3·n4). 결과를 보기 전에 고정한 규칙이다.
 *
 * <p>종목(보통주 {@code STOCK})마다 <b>연 단위 덮임률</b> = 셀 수 있는 달력 연도 중 그 종목 코드로 붙은 공시(프로필 유형)가 하나라도
 * 있는 연도의 비율. 셀 수 있는 연도 Y는 1월 1일 ~ 12월 31일 전체가 [첫 일봉일, 마지막 일봉일 − 365일] 안에 있고 공시 스냅샷이 그
 * 연도를 모든 유형에서 덮는 연도다. 마지막 365일을 빼는 것은 폐지 과정의 공시(거래정지·상장폐지 사유)가 폐지 종목 쪽 비율을
 * 끌어올리는 것을 막고, 폐지·유지 종목의 노출을 같은 잣대(연 단위)로 맞추려는 것이다(n4). 이름 변경 전 구간 공시를 못 붙인
 * 종목은 그 연도들이 비어 비율이 내려간다(n3).
 *
 * <p>상장폐지 = 일봉 스냅샷 마지막 거래일에 종목 이력 구간이 없는 종목. 셀 수 있는 연도가 없는 종목은 평균에서 빼고 수를 남긴다.
 * <b>통과</b>: 폐지 종목 평균 ≥ {@value #MIN_RATIO} × 유지 종목 평균, 그리고 셀 수 있는 폐지 종목이 {@value #MIN_DELISTED}개 이상.
 * 통과하지 못하면 그 선정의 판정은 "판정 불가(생존편향 미확인)"다.
 */
final class EdgeSurvivorship {

    static final double MIN_RATIO = 0.8;
    static final int MIN_DELISTED = 30;

    private EdgeSurvivorship() {
    }

    /**
     * @param delistedCounted 셀 수 있는 연도가 있는 폐지 종목 수
     * @param delistedSkipped 셀 수 있는 연도가 없어 뺀 폐지 종목 수
     */
    record Result(SortedSet<String> types, int delistedCounted, int delistedSkipped, double delistedMean, int listedCounted,
            int listedSkipped, double listedMean, boolean passed) {

        double ratio() {
            return listedMean == 0 ? Double.NaN : delistedMean / listedMean;
        }
    }

    static Result check(SnapshotDailyBarSource bars, SnapshotDisclosures disclosures, Set<String> types,
            List<LocalDate> tradingDays, LocalDate snapshotLast) {
        SortedSet<Symbol> symbols = new TreeSet<>();
        for (LocalDate d : tradingDays) {
            symbols.addAll(bars.universe(Market.KRX, d));
        }
        LocalDate first = tradingDays.get(0);
        int dc = 0;
        int ds = 0;
        int lc = 0;
        int ls = 0;
        double dsum = 0;
        double lsum = 0;
        for (Symbol s : symbols) {
            List<DailyBar> b = bars.bars(s, first, snapshotLast);
            if (b.isEmpty()) {
                continue;
            }
            LocalDate firstBar = b.get(0).date();
            LocalDate lastBar = b.get(b.size() - 1).date();
            Optional<InstrumentPeriod> p = bars.instrumentOn(s, lastBar);
            if (p.isEmpty() || p.get().kind() != InstrumentKind.STOCK) {
                continue;
            }
            boolean delisted = bars.instrumentOn(s, snapshotLast).isEmpty();
            OptionalDouble cov = coverage(firstBar, lastBar, y -> coversAll(disclosures, types, LocalDate.of(y, 1, 1),
                    LocalDate.of(y, 12, 31)), y -> disclosures.count(s, types, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)) > 0);
            if (cov.isEmpty()) {
                if (delisted) {
                    ds++;
                } else {
                    ls++;
                }
                continue;
            }
            double r = cov.getAsDouble();
            if (delisted) {
                dc++;
                dsum += r;
            } else {
                lc++;
                lsum += r;
            }
        }
        double dm = dc == 0 ? Double.NaN : dsum / dc;
        double lm = lc == 0 ? Double.NaN : lsum / lc;
        boolean passed = passes(dc, dm, lc, lm);
        return new Result(java.util.Collections.unmodifiableSortedSet(new TreeSet<>(types)), dc, ds, dm, lc, ls, lm, passed);
    }

    /**
     * 종목 하나의 연 단위 덮임률. 셀 수 있는 연도 = 1~12월 전체가 [firstBar, lastBar − 365일] 안이고 {@code yearCovered}인 연도.
     * 셀 수 있는 연도가 없으면 비어 있다.
     */
    static OptionalDouble coverage(LocalDate firstBar, LocalDate lastBar, IntPredicate yearCovered, IntPredicate yearHit) {
        LocalDate end = lastBar.minusDays(365);
        int eligible = 0;
        int hit = 0;
        for (int y = firstBar.getYear(); y <= end.getYear(); y++) {
            if (LocalDate.of(y, 1, 1).isBefore(firstBar) || LocalDate.of(y, 12, 31).isAfter(end) || !yearCovered.test(y)) {
                continue;
            }
            eligible++;
            if (yearHit.test(y)) {
                hit++;
            }
        }
        return eligible == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) hit / eligible);
    }

    /** 폐지 평균 ≥ 0.8 × 유지 평균, 셀 수 있는 폐지 종목 30개 이상, 유지 종목 1개 이상. */
    static boolean passes(int delistedCounted, double delistedMean, int listedCounted, double listedMean) {
        return delistedCounted >= MIN_DELISTED && listedCounted > 0 && !Double.isNaN(delistedMean)
                && delistedMean >= MIN_RATIO * listedMean;
    }

    private static boolean coversAll(SnapshotDisclosures d, Set<String> types, LocalDate from, LocalDate to) {
        for (String t : types) {
            if (!d.covers(t, from, to)) {
                return false;
            }
        }
        return true;
    }
}
