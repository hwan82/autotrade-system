package com.autotrade.domain.account;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 한 시점의 계좌 상태(불변). 리스크 검증과 화면 조회의 입력이다.
 * 포지션은 종목 순으로 정렬해 보관해 순회 결과가 결정론적이다.
 */
public record AccountSnapshot(AccountId accountId, Instant asOf, Money cash, SortedMap<Symbol, Position> positions) {

    public AccountSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(cash, "cash");
        TreeMap<Symbol, Position> copy = new TreeMap<>();
        for (Map.Entry<Symbol, Position> e : positions.entrySet()) {
            if (!e.getKey().equals(e.getValue().symbol())) {
                throw new IllegalArgumentException("포지션 키와 종목이 다릅니다: " + e.getKey());
            }
            if (!e.getValue().averagePrice().currency().equals(cash.currency())) {
                throw new IllegalArgumentException("계좌 통화와 다른 포지션 통화: " + e.getKey());
            }
            copy.put(e.getKey(), e.getValue());
        }
        positions = Collections.unmodifiableSortedMap(copy);
    }

    public long quantityOf(Symbol symbol) {
        Position p = positions.get(symbol);
        return p == null ? 0L : p.quantity();
    }
}
