package com.autotrade.marketdata.daily;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.QualityReport.Finding;
import com.autotrade.marketdata.daily.QualityReport.Rule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 일봉 품질 검사(MD-09, ADR-003 2.6). 순수 함수: 같은 입력이면 같은 결과(종목·날짜 순).
 *
 * <p>MISSING_BAR의 "시장 거래일"은 그 시장 일봉에 한 번이라도 나온 날짜로 본다. 캘린더가 없는 과거 기간에도
 * 쓸 수 있게 하려는 것이며, 시장 전체가 빠진 날은 잡지 못한다(한계).
 * UNADJUSTED_JUMP 기준은 KRX 가격제한폭 30%를 조금 넘는 35%, 미국은 제한폭이 없어 50%로 둔다 `[가정]`.
 */
public final class DailyBarQualityCheck {

    private static final BigDecimal KRX_JUMP = new BigDecimal("0.35");
    private static final BigDecimal US_JUMP = new BigDecimal("0.50");

    private DailyBarQualityCheck() {}

    public static QualityReport check(SnapshotManifest manifest, List<DailyBar> bars, List<InstrumentPeriod> history) {
        Objects.requireNonNull(manifest, "manifest");
        // 스냅샷 기준일은 생성 시각의 거래소 현지 날짜다(시장마다 다르다: 한국 아침은 미국 전날 저녁)
        java.time.Instant createdAt = java.time.Instant.parse(manifest.createdAt());
        List<Finding> findings = new ArrayList<>();
        Map<Symbol, List<DailyBar>> bySymbol = new TreeMap<>();
        Map<Market, NavigableSet<LocalDate>> marketDates = new TreeMap<>();
        for (DailyBar b : bars) {
            bySymbol.computeIfAbsent(b.symbol(), s -> new ArrayList<>()).add(b);
            marketDates.computeIfAbsent(b.symbol().market(), m -> new TreeSet<>()).add(b.date());
        }
        Map<Symbol, List<InstrumentPeriod>> periods = new TreeMap<>();
        for (InstrumentPeriod p : history) {
            periods.computeIfAbsent(p.symbol(), s -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<Symbol, List<DailyBar>> e : bySymbol.entrySet()) {
            Symbol sym = e.getKey();
            List<DailyBar> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparing(DailyBar::date));
            List<InstrumentPeriod> ps = periods.getOrDefault(sym, List.of());
            DailyBar prev = null;
            for (DailyBar b : list) {
                if (prev != null && prev.date().equals(b.date())) {
                    findings.add(new Finding(Rule.DUPLICATE_BAR, sym, b.date(), "같은 날짜 행이 둘 이상"));
                    continue;
                }
                checkRow(b, ps, localDate(createdAt, b.symbol().market()), findings);
                if (prev != null && valid(prev) && valid(b)) {
                    checkJump(prev, b, findings);
                }
                prev = b;
            }
            checkMissing(sym, list, ps, marketDates.get(sym.market()), findings);
        }
        findings.sort(Comparator.comparing((Finding f) -> f.symbol()).thenComparing(Finding::date)
                .thenComparing(f -> f.rule().ordinal()));
        return new QualityReport(manifest, bars.size(), findings);
    }

    private static LocalDate localDate(java.time.Instant t, Market market) {
        return t.atZone(com.autotrade.marketdata.calendar.ExchangeCalendar.bundled(market).zone()).toLocalDate();
    }

    private static void checkRow(DailyBar b, List<InstrumentPeriod> ps, LocalDate snapshotDate, List<Finding> out) {
        Symbol s = b.symbol();
        if (b.open().signum() <= 0 || b.high().signum() <= 0 || b.low().signum() <= 0 || b.close().signum() <= 0) {
            out.add(new Finding(Rule.NON_POSITIVE_PRICE, s, b.date(),
                    "O/H/L/C " + b.open() + "/" + b.high() + "/" + b.low() + "/" + b.close()));
        } else if (b.high().compareTo(b.open().max(b.close()).max(b.low())) < 0
                || b.low().compareTo(b.open().min(b.close())) > 0) {
            out.add(new Finding(Rule.OHLC_INCONSISTENT, s, b.date(),
                    "O/H/L/C " + b.open() + "/" + b.high() + "/" + b.low() + "/" + b.close()));
        }
        if (b.adjFactor().signum() <= 0) {
            out.add(new Finding(Rule.NON_POSITIVE_ADJ_FACTOR, s, b.date(), "adj_factor " + b.adjFactor()));
        }
        if (b.date().isAfter(snapshotDate)) {
            out.add(new Finding(Rule.AFTER_SNAPSHOT, s, b.date(), "스냅샷 " + snapshotDate));
        }
        if (ps.stream().noneMatch(p -> p.covers(b.date()))) {
            out.add(new Finding(Rule.OUTSIDE_LISTING, s, b.date(), ps.isEmpty() ? "종목 이력 없음" : "상장 기간 밖"));
        }
        if (b.volume() <= 0) {
            out.add(new Finding(Rule.ZERO_VOLUME, s, b.date(), "volume " + b.volume()));
        }
    }

    private static void checkJump(DailyBar prev, DailyBar b, List<Finding> out) {
        if (prev.adjFactor().compareTo(b.adjFactor()) != 0) {
            return;
        }
        BigDecimal change = b.close().divide(prev.close(), MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs();
        BigDecimal limit = b.symbol().market() == Market.KRX ? KRX_JUMP : US_JUMP;
        if (change.compareTo(limit) > 0) {
            out.add(new Finding(Rule.UNADJUSTED_JUMP, b.symbol(), b.date(),
                    "전일 종가 " + prev.close() + " → " + b.close()));
        }
    }

    private static void checkMissing(Symbol sym, List<DailyBar> list, List<InstrumentPeriod> ps,
            NavigableSet<LocalDate> marketDays, List<Finding> out) {
        if (marketDays == null || ps.isEmpty()) {
            return;
        }
        TreeSet<LocalDate> have = new TreeSet<>();
        for (DailyBar b : list) {
            have.add(b.date());
        }
        for (LocalDate d : marketDays) {
            if (!have.contains(d) && ps.stream().anyMatch(p -> p.covers(d))) {
                out.add(new Finding(Rule.MISSING_BAR, sym, d, "시장 거래일인데 일봉 없음"));
            }
        }
    }

    static boolean valid(DailyBar b) {
        return b.open().signum() > 0 && b.high().signum() > 0 && b.low().signum() > 0 && b.close().signum() > 0;
    }
}
