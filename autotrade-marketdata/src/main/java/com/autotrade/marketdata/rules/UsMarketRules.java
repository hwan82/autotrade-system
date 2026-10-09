package com.autotrade.marketdata.rules;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 미국 정규장 규칙(D-015, ADR-003 2.5). 거래 시간은 NYSE 캘린더, 호가 단위는 SEC Regulation NMS Rule 612:
 * 1달러 이상 0.01달러, 1달러 미만 0.0001달러 `[사실]`(17 CFR 242.612).
 * `[가정]` 2024년 Rule 612 개정(일부 종목 0.005달러 단위)의 시행 여부·대상은 확인하지 못했다. 시행되면 종목별 단위를
 * 받아야 하므로 이 구현을 바꾼다(ADR-003 5장 확인 항목).
 */
public final class UsMarketRules implements MarketRules {

    private static final BigDecimal ONE_DOLLAR = BigDecimal.ONE;
    private static final BigDecimal PENNY = new BigDecimal("0.01");
    private static final BigDecimal SUB_PENNY = new BigDecimal("0.0001");

    private final ExchangeCalendar calendar;

    public UsMarketRules(ExchangeCalendar calendar) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        if (calendar.market() != Market.US) {
            throw new IllegalArgumentException("US 캘린더가 아닙니다: " + calendar.market());
        }
    }

    @Override
    public Market market() {
        return Market.US;
    }

    @Override
    public ZoneId zone() {
        return calendar.zone();
    }

    @Override
    public boolean isRegularSession(Instant instant) {
        return calendar.isRegularSession(instant);
    }

    @Override
    public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
        if (symbol.market() != Market.US) {
            throw new IllegalArgumentException("US 종목이 아닙니다: " + symbol);
        }
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다: " + price);
        }
        return price.compareTo(ONE_DOLLAR) >= 0 ? PENNY : SUB_PENNY;
    }

    public ExchangeCalendar calendar() {
        return calendar;
    }
}
