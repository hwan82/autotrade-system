package com.autotrade.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TradeSignalTest {

    private static final Symbol S = Symbol.krx("005930");

    @Test
    void 공매도와_의도_불일치를_거부한다() {
        assertThatThrownBy(() -> new TradeSignal(S, Side.SELL, OrderIntent.ENTRY, OrderType.MARKET, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradeSignal(S, Side.BUY, OrderIntent.STOP_LOSS, OrderType.MARKET, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 수량과_가격_규칙() {
        assertThatThrownBy(() -> TradeSignal.marketBuy(S, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradeSignal(S, Side.BUY, OrderIntent.ENTRY, OrderType.LIMIT, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradeSignal(S, Side.BUY, OrderIntent.ENTRY, OrderType.MARKET, BigDecimal.TEN, 1))
                .isInstanceOf(IllegalArgumentException.class);
        TradeSignal s = TradeSignal.limitSell(S, OrderIntent.STOP_LOSS, new BigDecimal("69000"), 3);
        assertThat(s.side()).isEqualTo(Side.SELL);
        assertThat(s.intent().isProtective()).isTrue();
    }
}
