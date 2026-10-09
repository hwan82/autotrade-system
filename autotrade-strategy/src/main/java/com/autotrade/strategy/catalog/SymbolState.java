package com.autotrade.strategy.catalog;

import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 종목 하나의 장중 상태: 지금까지 받은 이벤트만으로 만든다(미래 정보 없음). 전략 인스턴스 안에서만 쓰고 공유하지 않는다.
 *
 * <p>금액·가격은 BigDecimal, 거래량은 long이다. 분 단위 집계는 거래소 현지 시각의 분(0~1439)으로 나눈다.
 */
public final class SymbolState {

    private static final int MINUTES = 24 * 60;
    /** 호가 불균형 계산에 쓰는 단계 수. */
    static final int DEPTH_LEVELS = 5;

    private final Symbol symbol;
    private LocalDate day;
    private BigDecimal lastPrice;
    private BigDecimal bestBid;
    private BigDecimal bestAsk;
    private long bidDepth;
    private long askDepth;
    private BigDecimal dayOpen;
    private BigDecimal dayHigh;
    private BigDecimal dayLow;
    private BigDecimal prevDayHigh;
    private BigDecimal prevDayClose;
    private BigDecimal vwapNumerator = BigDecimal.ZERO;
    private long dayVolume;
    private int firstMinute = -1;
    private final long[] minuteVolume = new long[MINUTES];
    private final BigDecimal[] minuteClose = new BigDecimal[MINUTES];
    private final List<BigDecimal> dailyCloses = new ArrayList<>();

    SymbolState(Symbol symbol) {
        this.symbol = symbol;
    }

    public Symbol symbol() {
        return symbol;
    }

    /** 새 거래일이면 전일 값을 넘기고 당일 집계를 비운다. 새 거래일이면 true. */
    boolean roll(LocalDate date) {
        if (date.equals(day)) {
            return false;
        }
        if (day != null && dayHigh != null) {
            prevDayHigh = dayHigh;
            prevDayClose = lastPrice;
            dailyCloses.add(lastPrice);
        }
        day = date;
        dayOpen = null;
        dayHigh = null;
        dayLow = null;
        vwapNumerator = BigDecimal.ZERO;
        dayVolume = 0;
        firstMinute = -1;
        Arrays.fill(minuteVolume, 0L);
        Arrays.fill(minuteClose, null);
        return true;
    }

    void update(MarketEvent event, LocalTime time) {
        if (event instanceof TradeTick t) {
            BigDecimal p = t.price();
            lastPrice = p;
            if (dayOpen == null) {
                dayOpen = p;
                dayHigh = p;
                dayLow = p;
            } else {
                dayHigh = dayHigh.max(p);
                dayLow = dayLow.min(p);
            }
            vwapNumerator = vwapNumerator.add(p.multiply(BigDecimal.valueOf(t.quantity())));
            dayVolume += t.quantity();
            int m = minuteOf(time);
            if (firstMinute < 0) {
                firstMinute = m;
            }
            minuteVolume[m] += t.quantity();
            minuteClose[m] = p;
        } else if (event instanceof OrderBookSnapshot b) {
            bestBid = b.bids().isEmpty() ? null : b.bids().get(0).price();
            bestAsk = b.asks().isEmpty() ? null : b.asks().get(0).price();
            bidDepth = depth(b.bids());
            askDepth = depth(b.asks());
        }
    }

    public LocalDate day() {
        return day;
    }

    public BigDecimal lastPrice() {
        return lastPrice;
    }

    public BigDecimal bestBid() {
        return bestBid;
    }

    public BigDecimal bestAsk() {
        return bestAsk;
    }

    /** 매수 가격 기준: 최우선 매도 호가, 없으면 마지막 체결가. */
    public BigDecimal entryReference() {
        return bestAsk != null ? bestAsk : lastPrice;
    }

    public long bidDepth() {
        return bidDepth;
    }

    public long askDepth() {
        return askDepth;
    }

    public BigDecimal dayOpen() {
        return dayOpen;
    }

    public BigDecimal dayHigh() {
        return dayHigh;
    }

    public BigDecimal dayLow() {
        return dayLow;
    }

    public BigDecimal prevDayHigh() {
        return prevDayHigh;
    }

    public BigDecimal prevDayClose() {
        return prevDayClose;
    }

    /** 오늘 지금까지의 거래량 가중 평균가. 체결이 없으면 null. */
    public BigDecimal vwap() {
        return dayVolume == 0 ? null : vwapNumerator.divide(BigDecimal.valueOf(dayVolume), MathContext.DECIMAL64);
    }

    public long dayVolume() {
        return dayVolume;
    }

    /** 끝난 거래일의 마지막 체결가(오래된 것부터). 오늘은 들어 있지 않다. */
    public List<BigDecimal> dailyCloses() {
        return Collections.unmodifiableList(dailyCloses);
    }

    /** 오늘 첫 체결 이후 지난 분 수(지금 분 포함). 체결이 없으면 0. */
    public int minutesSinceFirstTrade(LocalTime now) {
        return firstMinute < 0 ? 0 : minuteOf(now) - firstMinute + 1;
    }

    /** 지금 분을 포함한 최근 {@code minutes}분 거래량. */
    public long recentVolume(LocalTime now, int minutes) {
        int end = minuteOf(now);
        long sum = 0;
        for (int m = Math.max(0, end - minutes + 1); m <= end; m++) {
            sum += minuteVolume[m];
        }
        return sum;
    }

    /**
     * 최근 {@code minutes}분 거래량 / 오늘 그 앞 구간의 같은 길이 평균 거래량. 앞 구간이 {@code minutes}분보다 짧거나
     * 거래가 없으면 null(판단 불가).
     */
    public BigDecimal volumeRatio(LocalTime now, int minutes) {
        int elapsedBefore = minutesSinceFirstTrade(now) - minutes;
        if (elapsedBefore < minutes) {
            return null;
        }
        long recent = recentVolume(now, minutes);
        long before = dayVolume - recent;
        if (before <= 0) {
            return null;
        }
        BigDecimal avgPerWindow = BigDecimal.valueOf(before).multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(elapsedBefore), MathContext.DECIMAL64);
        return BigDecimal.valueOf(recent).divide(avgPerWindow, MathContext.DECIMAL64);
    }

    /** {@code minutes}분 전(그 분의 마지막 체결가, 없으면 그 앞의 가장 가까운 분). 오늘 범위 밖이면 null. */
    public BigDecimal priceMinutesAgo(LocalTime now, int minutes) {
        int target = minuteOf(now) - minutes;
        for (int m = target; m >= Math.max(0, firstMinute); m--) {
            if (minuteClose[m] != null) {
                return minuteClose[m];
            }
        }
        return null;
    }

    private static int minuteOf(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static long depth(List<PriceLevel> levels) {
        long sum = 0;
        for (int i = 0; i < Math.min(DEPTH_LEVELS, levels.size()); i++) {
            sum += levels.get(i).quantity();
        }
        return sum;
    }
}
