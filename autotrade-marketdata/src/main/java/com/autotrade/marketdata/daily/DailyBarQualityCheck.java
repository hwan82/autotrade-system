package com.autotrade.marketdata.daily;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.QualityReport.Finding;
import com.autotrade.marketdata.daily.QualityReport.Rule;
import com.autotrade.marketdata.rules.BundledMarketRules;
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
 * UNADJUSTED_JUMP 기준은 시장 규칙의 하루 가격 제한폭({@link MarketRules#dailyPriceLimit})보다 5%p 큰 값(KRX 주식 35%),
 * 제한폭이 없으면 50%(미국)로 둔다 `[가정]`. 시장별 값은 여기서 분기하지 않고 {@link MarketRules}에서 읽는다(OVS-02, D-084).
 */
public final class DailyBarQualityCheck {

    /** 제한폭 위 여유. 제한폭 끝까지 움직인 정상 거래일을 급변으로 잡지 않게 한다. */
    private static final BigDecimal JUMP_MARGIN = new BigDecimal("0.05");
    /** 하루 제한폭이 없는 시장·종목의 급변 기준. */
    private static final BigDecimal NO_LIMIT_JUMP = new BigDecimal("0.50");

    private DailyBarQualityCheck() {}

    public static QualityReport check(SnapshotManifest manifest, List<DailyBar> bars, List<InstrumentPeriod> history) {
        Objects.requireNonNull(manifest, "manifest");
        // 스냅샷 기준일은 생성 시각의 거래소 현지 날짜다(시장마다 다르다: 한국 아침은 미국 전날 저녁)
        java.time.Instant createdAt = java.time.Instant.parse(manifest.createdAt());
        // 캘린더 읽기는 비싸다(행마다 하면 20년치 검사가 수십 분): 시장마다 한 번만 계산한다
        Map<Market, MarketRules> rules = new java.util.EnumMap<>(Market.class);
        Map<Market, LocalDate> snapshotDates = new java.util.EnumMap<>(Market.class);
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
                checkRow(b, ps, snapshotDates.computeIfAbsent(sym.market(), m -> rulesOf(rules, m).tradingDate(createdAt)), findings);
                if (prev != null && valid(prev) && valid(b)) {
                    checkJump(prev, b, rulesOf(rules, sym.market()), findings);
                }
                prev = b;
            }
            checkMissing(sym, list, ps, marketDates.get(sym.market()), findings);
        }
        findings.sort(Comparator.comparing((Finding f) -> f.symbol()).thenComparing(Finding::date)
                .thenComparing(f -> f.rule().ordinal()));
        return new QualityReport(manifest, bars.size(), findings);
    }

    /**
     * 스냅샷 폴더의 일봉을 메모리에 다 올리지 않고 같은 규칙으로 검사한다(수집기 품질 리포트, QA PR #39 F1).
     * {@link #check}와 같은 결과를 낸다. 대신 {@code daily_bars.csv}가 날짜 오름차순이어야 한다(수집기가 쓰는 순서).
     * 해시·행 수는 {@link SnapshotFiles#readVerified}와 같이 확인한다. 메모리는 종목 수·발견 수에 비례하고 행 수와 무관하다.
     *
     * @param perDate 날짜 하나를 읽을 때마다 부른다(수집기의 장 시간 보호 구간 확인). 예외를 던지면 검사를 멈춘다
     */
    public static QualityReport checkSnapshot(java.nio.file.Path dir, Runnable perDate) throws java.io.IOException {
        SnapshotManifest manifest = SnapshotFiles.readManifest(dir);
        List<InstrumentPeriod> history = SnapshotDailyBarSource.periods(
                SnapshotFiles.readVerified(dir, manifest, SnapshotManifest.INSTRUMENT_HISTORY, SnapshotDailyBarSource.HISTORY_HEADER));
        SnapshotManifest.FileEntry entry = manifest.files().stream()
                .filter(f -> f.name().equals(SnapshotManifest.DAILY_BARS)).findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest에 " + SnapshotManifest.DAILY_BARS + " 항목이 없습니다"));
        java.nio.file.Path file = dir.resolve(SnapshotManifest.DAILY_BARS);
        if (!java.nio.file.Files.exists(file)) {
            throw new IllegalStateException("스냅샷 파일이 없습니다: " + SnapshotManifest.DAILY_BARS);
        }
        if (!com.autotrade.marketdata.recording.Sha256.ofFile(file).equalsIgnoreCase(entry.sha256())) {
            throw new IllegalStateException(SnapshotManifest.DAILY_BARS + " SHA-256이 manifest와 다릅니다");
        }
        java.time.Instant createdAt = java.time.Instant.parse(manifest.createdAt());
        Map<Market, MarketRules> rules = new java.util.EnumMap<>(Market.class);
        Map<Market, LocalDate> snapshotDates = new java.util.EnumMap<>(Market.class);
        Map<Symbol, List<InstrumentPeriod>> periods = new TreeMap<>();
        for (InstrumentPeriod p : history) {
            periods.computeIfAbsent(p.symbol(), x -> new ArrayList<>()).add(p);
        }
        List<Finding> findings = new ArrayList<>();
        Map<Symbol, DailyBar> prevBar = new java.util.HashMap<>();
        Map<Symbol, List<Finding>> missing = new TreeMap<>();
        java.util.Set<Symbol> seenEver = new java.util.HashSet<>();
        long rows = 0;
        try (java.io.BufferedReader r = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) {
                throw new IllegalArgumentException(SnapshotManifest.DAILY_BARS + "이 비어 있습니다");
            }
            if (header.startsWith("\uFEFF")) {
                header = header.substring(1);
            }
            if (!Csv.split(header).equals(SnapshotDailyBarSource.BAR_HEADER)) {
                throw new IllegalArgumentException(SnapshotManifest.DAILY_BARS + " 헤더가 스키마와 다릅니다: " + header);
            }
            LocalDate day = null;
            Map<Symbol, DailyBar> today = new java.util.LinkedHashMap<>();
            String line;
            int n = 1;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isEmpty()) {
                    continue;
                }
                List<String> f = Csv.split(line);
                if (f.size() != SnapshotDailyBarSource.BAR_HEADER.size()) {
                    throw new IllegalArgumentException(SnapshotManifest.DAILY_BARS + " " + n + "번째 줄 열 수가 다릅니다");
                }
                DailyBar b = SnapshotDailyBarSource.bar(f);
                rows++;
                if (day == null || !b.date().equals(day)) {
                    if (day != null && b.date().isBefore(day)) {
                        throw new IllegalStateException(SnapshotManifest.DAILY_BARS + "가 날짜 순이 아닙니다(" + n + "번째 줄)");
                    }
                    if (day != null) {
                        endOfDay(day, today, periods, missing);
                    }
                    day = b.date();
                    today.clear();
                    perDate.run();
                }
                Symbol sym = b.symbol();
                if (today.containsKey(sym)) {
                    findings.add(new Finding(Rule.DUPLICATE_BAR, sym, b.date(), "같은 날짜 행이 둘 이상"));
                    continue;
                }
                today.put(sym, b);
                seenEver.add(sym);
                checkRow(b, periods.getOrDefault(sym, List.of()),
                        snapshotDates.computeIfAbsent(sym.market(), m -> rulesOf(rules, m).tradingDate(createdAt)), findings);
                DailyBar prev = prevBar.put(sym, b);
                if (prev != null && valid(prev) && valid(b)) {
                    checkJump(prev, b, rulesOf(rules, sym.market()), findings);
                }
            }
            if (day != null) {
                endOfDay(day, today, periods, missing);
            }
        }
        if (rows != entry.rows()) {
            throw new IllegalStateException(SnapshotManifest.DAILY_BARS + " 행 수가 manifest와 다릅니다: " + rows + " != " + entry.rows());
        }
        missing.forEach((sym, list) -> {
            if (seenEver.contains(sym)) {
                findings.addAll(list);
            }
        });
        findings.sort(Comparator.comparing((Finding f) -> f.symbol()).thenComparing(Finding::date)
                .thenComparing(f -> f.rule().ordinal()));
        return new QualityReport(manifest, rows, findings);
    }

    /** 그날 일봉이 하나라도 있는 시장의, 상장 기간인데 그날 일봉이 없는 종목(MISSING_BAR 후보). */
    private static void endOfDay(LocalDate day, Map<Symbol, DailyBar> today, Map<Symbol, List<InstrumentPeriod>> periods,
            Map<Symbol, List<Finding>> missing) {
        java.util.Set<Market> markets = java.util.EnumSet.noneOf(Market.class);
        for (Symbol s : today.keySet()) {
            markets.add(s.market());
        }
        for (Map.Entry<Symbol, List<InstrumentPeriod>> e : periods.entrySet()) {
            Symbol s = e.getKey();
            if (markets.contains(s.market()) && !today.containsKey(s) && e.getValue().stream().anyMatch(p -> p.covers(day))) {
                missing.computeIfAbsent(s, x -> new ArrayList<>()).add(new Finding(Rule.MISSING_BAR, s, day, "시장 거래일인데 일봉 없음"));
            }
        }
    }

    private static MarketRules rulesOf(Map<Market, MarketRules> cache, Market market) {
        return cache.computeIfAbsent(market, BundledMarketRules::of);
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

    private static void checkJump(DailyBar prev, DailyBar b, MarketRules rules, List<Finding> out) {
        if (prev.adjFactor().compareTo(b.adjFactor()) != 0) {
            return;
        }
        BigDecimal change = b.close().divide(prev.close(), MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs();
        BigDecimal limit = rules.dailyPriceLimit(b.symbol()).map(JUMP_MARGIN::add).orElse(NO_LIMIT_JUMP);
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
