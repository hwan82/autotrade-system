package com.autotrade.domain.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 체결 이벤트. 가격은 시장 통화 단위(KRX는 원, US는 달러). */
public record TradeTick(
        Symbol symbol, long sequence, Instant exchangeTime, Instant receivedAt, BigDecimal price, long quantity)
        implements MarketEvent {

    public TradeTick {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(exchangeTime, "exchangeTime");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(price, "price");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("체결가는 0보다 커야 합니다: " + price);
        }
        price = price.stripTrailingZeros(); // 직렬화 왕복 후에도 equals가 같도록 스케일 정규화
        if (quantity <= 0) {
            throw new IllegalArgumentException("체결 수량은 0보다 커야 합니다: " + quantity);
        }
    }
}
