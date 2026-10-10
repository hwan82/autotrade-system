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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SortedSet;
import java.util.TreeMap;

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

    /**
     * @param shares 일봉 스냅샷의 {@code listed_shares.csv}. 없으면 null(시가총액 조건은 결측으로 탈락)
     * @param financials 재무 스냅샷. 없으면 null(재무 조건은 결측으로 탈락)
     */
    public SnapshotSelectionData(Market market, SnapshotDailyBarSource bars, SnapshotListedShares shares,
            SnapshotFinancials financials, TradingDays tradingDays, String calendarDigest) {
        this.market = Objects.requireNonNull(market, "market");
        this.bars = Objects.requireNonNull(bars, "bars");
        this.shares = shares;
        this.financials = financials;
        this.tradingDays = Objects.requireNonNull(tradingDays, "tradingDays");
        this.calendarDigest = Objects.requireNonNull(calendarDigest, "calendarDigest");
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
                calendarDigest(calendar));
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
        return Optional.of(new FinancialSnapshotInfo(m.snapshotId(), fileSha(m, SnapshotManifest.FINANCIAL_FILINGS),
                collected));
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
