package com.autotrade.domain.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderRequestTest {

    private static final AccountId ACC = new AccountId("EXP-1-A01");
    private static final Symbol SAMSUNG = Symbol.krx("005930");
    private static final IdempotencyKey BUY_KEY = IdempotencyKey.parse("20261008-005930-BUY-1");

    @Test
    void 멱등키와_종목_방향이_다르면_거부한다() {
        assertThatThrownBy(() -> new OrderRequest(
                        BUY_KEY, ACC, Symbol.krx("000660"), Side.BUY, OrderType.LIMIT, new BigDecimal("100"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRequest(
                        BUY_KEY, ACC, SAMSUNG, Side.SELL, OrderType.LIMIT, new BigDecimal("100"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 지정가에는_가격이_시장가에는_가격이_없어야_한다() {
        assertThatThrownBy(() -> new OrderRequest(BUY_KEY, ACC, SAMSUNG, Side.BUY, OrderType.LIMIT, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRequest(
                        BUY_KEY, ACC, SAMSUNG, Side.BUY, OrderType.MARKET, new BigDecimal("100"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 수량은_양수여야_한다() {
        assertThatThrownBy(() -> new OrderRequest(BUY_KEY, ACC, SAMSUNG, Side.BUY, OrderType.MARKET, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
