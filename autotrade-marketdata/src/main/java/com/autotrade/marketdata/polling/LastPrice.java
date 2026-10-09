package com.autotrade.marketdata.polling;

import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 폴링으로 받은 현재가(체결 이벤트가 아니다: 수량이 없고 그 사이 체결을 모른다). 보유 종목 보호 감시용(D-008).
 *
 * @param priceTime 증권사가 준 가격 시각
 * @param receivedAt 이 시스템이 받은 시각
 */
public record LastPrice(Symbol symbol, BigDecimal price, Instant priceTime, Instant receivedAt, String sourceId) {

    public LastPrice {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(priceTime, "priceTime");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(sourceId, "sourceId");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("현재가는 0보다 커야 합니다: " + price);
        }
        price = price.stripTrailingZeros();
    }
}
