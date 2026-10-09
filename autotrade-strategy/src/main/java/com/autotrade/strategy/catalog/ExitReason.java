package com.autotrade.strategy.catalog;

import com.autotrade.domain.order.OrderIntent;

/** 청산 사유 코드(STR-09 청산 유형 카탈로그). 손절만 {@link OrderIntent#STOP_LOSS}로 나간다. */
public enum ExitReason {
    STOP_LOSS(OrderIntent.STOP_LOSS),
    TAKE_PROFIT(OrderIntent.EXIT),
    TRAILING_STOP(OrderIntent.EXIT),
    TIME_EXIT(OrderIntent.EXIT),
    LOGIC_EXIT(OrderIntent.EXIT),
    END_OF_DAY(OrderIntent.EXIT);

    private final OrderIntent intent;

    ExitReason(OrderIntent intent) {
        this.intent = intent;
    }

    public OrderIntent intent() {
        return intent;
    }
}
