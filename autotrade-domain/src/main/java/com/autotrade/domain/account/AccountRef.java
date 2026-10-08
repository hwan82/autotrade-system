package com.autotrade.domain.account;

import com.autotrade.domain.market.Market;
import java.util.Objects;

/**
 * 실행 단위 (증권사, 시장, 계좌)(overseas-design.md 4장). 주문 출구를 고를 때 계좌의 증권사를 이 값으로 확인한다(D-010).
 */
public record AccountRef(AccountId id, BrokerId broker, Market market) {

    public AccountRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(broker, "broker");
        Objects.requireNonNull(market, "market");
    }
}
