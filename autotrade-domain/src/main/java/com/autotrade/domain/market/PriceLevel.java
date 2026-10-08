package com.autotrade.domain.market;

import java.math.BigDecimal;
import java.util.Objects;

/** 호가 한 단계(가격과 잔량). */
public record PriceLevel(BigDecimal price, long quantity) {

    public PriceLevel {
        Objects.requireNonNull(price, "price");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("호가는 0보다 커야 합니다: " + price);
        }
        price = price.stripTrailingZeros(); // 직렬화 왕복 후에도 equals가 같도록 스케일 정규화
        if (quantity < 0) {
            throw new IllegalArgumentException("잔량은 음수일 수 없습니다: " + quantity);
        }
    }
}
