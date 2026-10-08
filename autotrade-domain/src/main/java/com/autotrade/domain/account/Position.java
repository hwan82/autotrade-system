package com.autotrade.domain.account;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import java.util.Objects;

/** 보유 포지션. 1단계는 공매도가 없으므로 수량은 양수. */
public record Position(Symbol symbol, long quantity, Money averagePrice) {

    public Position {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(averagePrice, "averagePrice");
        if (quantity <= 0) {
            throw new IllegalArgumentException("보유 수량은 0보다 커야 합니다: " + quantity);
        }
    }
}
