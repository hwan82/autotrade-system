package com.autotrade.selection.calc;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.daily.SnapshotListedShares;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.financial.FinancialFiling;
import com.autotrade.marketdata.financial.SnapshotFinancials;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 수집 스냅샷(D-019, ADR-008) 폴더에서 읽은 선정 입력. 일봉 스냅샷(상장주식수 파일 포함 가능)과 재무 스냅샷은 따로 받는다.
 * 실험실 리플레이와 생산 일괄 명령(후속)이 같은 클래스를 쓴다.
 */
public final class SnapshotSelectionData implements SelectionData {

    private final Market market;
    private final SnapshotDailyBarSource bars;
    private final SnapshotListedShares shares;
    private final SnapshotFinancials financials;
    private final Map<Symbol, List<FinancialFiling>> filingsBySymbol;
    private final TradingDays tradingDays;
    private final String calendarDigest;
    private final ZoneId zone;
    private final LocalDate barsLastTradingDate;
    private final LocalDate sharesAsOf;
    private final DisclosureInput disclosures;
    /** 실제 거래대금 파일을 읽는 함수. 파일이 없으면 null. 처음 쓸 때 한 번만 부른다(쓰지 않는 선정은 970만 행을 읽지 않음). */
    private final Supplier<? extends TurnoverInput> turnoverLoader;
    private TurnoverInput turnover;

    /**
     * @param shares 일봉 스냅샷의 {@code listed_shares.csv}. 없으면 null(시가총액 조건은 결측으로 탈락)
     * @param financials 재무 스냅샷. 없으면 null
     * @param zone 거래소 시간대(재무 수집 시각을 현지 날짜로 바꿀 때)
     * @throws IllegalStateException 일봉 manifest에 {@code coverage.lastTradingDate}가 없거나, 상장주식수를 받았는데
     *     {@code coverage.sharesAsOf}가 없을 때(입력 기준일을 모르면 선정 결과에 적을 수 없다)
     */
    public SnapshotSelectionData(Market market, SnapshotDailyBarSource bars, SnapshotListedShares shares,
            SnapshotFinancials financials, TradingDays tradingDays, String calendarDigest, ZoneId zone) {
        this(market, bars, shares, financials, null, tradingDays, calendarDigest, zone);
    }

    /** @param disclosures 공시 스냅샷({@link SnapshotDisclosures}). 없으면 null(공시 조건은 계산 실패) */
    public SnapshotSelectionData(Market market, SnapshotDailyBarSource bars, SnapshotListedShares shares,
            SnapshotFinancials financials, DisclosureInput disclosures, TradingDays tradingDays, String calendarDigest,
            ZoneId zone) {
        this(market, bars, shares, financials, disclosures, null, tradingDays, calendarDigest, zone);
    }

    /**
     * @param turnover 일봉 스냅샷의 실제 거래대금 파일을 읽는 함수(보통 {@code () -> SnapshotTurnover.load(일봉 폴더)}, D-139).
     *     파일이 없으면 null(실제 거래대금 조건은 계산 실패). 처음 {@link #turnover()}를 부를 때 한 번 부르고, 실패하면 다음에 다시 부른다
     */
    public SnapshotSelectionData(Market market, SnapshotDailyBarSource bars, SnapshotListedShares shares,
            SnapshotFinancials financials, DisclosureInput disclosures, Supplier<? extends TurnoverInput> turnover,
            TradingDays tradingDays, String calendarDigest, ZoneId zone) {
        this.disclosures = disclosures;
        this.turnoverLoader = turnover;
        this.market = Objects.requireNonNull(market, "market");
        this.bars = Objects.requireNonNull(bars, "bars");
        this.shares = shares;
        this.financials = financials;
        this.tradingDays = Objects.requireNonNull(tradingDays, "tradingDays");
        this.calendarDigest = Objects.requireNonNull(calendarDigest, "calendarDigest");
        this.zone = Objects.requireNonNull(zone, "zone");
        SnapshotManifest.Coverage coverage = bars.manifest().coverage();
        this.barsLastTradingDate = date(coverage == null ? null : coverage.lastTradingDate(),
                "일봉 스냅샷 manifest에 coverage.lastTradingDate가 없습니다: " + bars.snapshotId());
        this.sharesAsOf = shares == null ? null : date(coverage == null ? null : coverage.sharesAsOf(),
                "일봉 스냅샷 manifest에 coverage.sharesAsOf가 없습니다: " + bars.snapshotId());
        Map<Symbol, List<FinancialFiling>> by = new TreeMap<>();
        if (financials != null) {
            for (FinancialFiling f : financials.filings()) {
                by.computeIfAbsent(f.symbol(), s -> new ArrayList<>()).add(f);
            }
        }
        by.replaceAll((s, l) -> List.copyOf(l));
        this.filingsBySymbol = Map.copyOf(by);
    }

    /** 캘린더 하나로 거래일과 출처 요약을 함께 정한다. */
    public static SnapshotSelectionData of(SnapshotDailyBarSource bars, SnapshotListedShares shares,
            SnapshotFinancials financials, ExchangeCalendar calendar) {
        return new SnapshotSelectionData(calendar.market(), bars, shares, financials, TradingDays.of(calendar),
                calendarDigest(calendar), calendar.zone());
    }

    /** 공시 스냅샷까지 받는 형태. */
    public static SnapshotSelectionData of(SnapshotDailyBarSource bars, SnapshotListedShares shares,
            SnapshotFinancials financials, DisclosureInput disclosures, ExchangeCalendar calendar) {
        return new SnapshotSelectionData(calendar.market(), bars, shares, financials, disclosures, TradingDays.of(calendar),
                calendarDigest(calendar), calendar.zone());
    }

    private static LocalDate date(String value, String missing) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(missing);
        }
        return LocalDate.parse(value);
    }

    /** 캘린더 출처 표({@code 키=값} 줄, 키 정렬)와 수록 범위의 SHA-256. */
    public static String calendarDigest(ExchangeCalendar calendar) {
        StringBuilder sb = new StringBuilder();
        sb.append(calendar.market()).append('\n').append(calendar.coverageFrom()).append('~')
                .append(calendar.coverageTo()).append('\n');
        new TreeMap<>(calendar.sources()).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sha256(sb.toString());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    @Override
    public Market market() {
        return market;
    }

    @Override
    public TradingDays tradingDays() {
        return tradingDays;
    }

    @Override
    public SortedSet<Symbol> universe(LocalDate date) {
        return bars.universe(market, date);
    }

    @Override
    public Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date) {
        return bars.instrumentOn(symbol, date);
    }

    @Override
    public List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to) {
        return bars.bars(symbol, from, to);
    }

    @Override
    public OptionalLong listedShares(Symbol symbol, LocalDate date) {
        return shares == null ? OptionalLong.empty() : shares.asOf(symbol, date);
    }

    @Override
    public List<FinancialFiling> filings(Symbol symbol) {
        return filingsBySymbol.getOrDefault(symbol, List.of());
    }

    @Override
    public LocalDate barsLastTradingDate() {
        return barsLastTradingDate;
    }

    @Override
    public Optional<LocalDate> sharesAsOf() {
        return Optional.ofNullable(sharesAsOf);
    }

    @Override
    public String barsSnapshotId() {
        return bars.snapshotId();
    }

    @Override
    public String barsSha256() {
        return fileSha(bars.manifest(), SnapshotManifest.DAILY_BARS);
    }

    @Override
    public Optional<String> sharesSnapshotId() {
        return shares == null ? Optional.empty() : Optional.of(bars.snapshotId());
    }

    @Override
    public Optional<FinancialSnapshotInfo> financials() {
        if (financials == null) {
            return Optional.empty();
        }
        SnapshotManifest m = financials.manifest();
        String collected = m.coverage() == null ? null : m.coverage().lastCollectedAt();
        LocalDate collectedDate = null;
        if (collected != null && !collected.isBlank()) {
            try {
                collectedDate = Instant.parse(collected).atZone(zone).toLocalDate();
            } catch (DateTimeParseException e) {
                throw new IllegalStateException("재무 스냅샷 coverage.lastCollectedAt 형식이 틀립니다: " + m.snapshotId(), e);
            }
        }
        return Optional.of(new FinancialSnapshotInfo(m.snapshotId(), fileSha(m, SnapshotManifest.FINANCIAL_FILINGS),
                collected, collectedDate));
    }

    @Override
    public Optional<DisclosureInput> disclosures() {
        return Optional.ofNullable(disclosures);
    }

    @Override
    public synchronized Optional<TurnoverInput> turnover() {
        if (turnoverLoader == null) {
            return Optional.empty();
        }
        if (turnover == null) {
            TurnoverInput in = Objects.requireNonNull(turnoverLoader.get(), "turnover");
            if (!in.snapshotId().equals(bars.snapshotId())) {
                throw new IllegalStateException("실제 거래대금 파일의 스냅샷 ID가 일봉과 다릅니다: " + in.snapshotId() + " ≠ "
                        + bars.snapshotId());
            }
            turnover = in;
        }
        return Optional.of(turnover);
    }

    @Override
    public String calendarDigest() {
        return calendarDigest;
    }

    private static String fileSha(SnapshotManifest m, String name) {
        return m.files().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest에 " + name + "이 없습니다: " + m.snapshotId()))
                .sha256();
    }
}
