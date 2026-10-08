package com.autotrade.sim.cost;

import com.autotrade.domain.Money;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 체결 건 하나의 비용. 항목별로 반올림된 금액과 열(COMMISSION, TAX)별 합계.
 *
 * @param items 항목 이름 → 금액(반올림 후). 이름 순으로 정렬
 */
public record FeeBreakdown(Money commission, Money tax, SortedMap<String, Money> items) {

    public FeeBreakdown {
        Objects.requireNonNull(commission, "commission");
        Objects.requireNonNull(tax, "tax");
        items = Collections.unmodifiableSortedMap(new TreeMap<>(items));
    }

    public Money total() {
        return commission.plus(tax);
    }
}
