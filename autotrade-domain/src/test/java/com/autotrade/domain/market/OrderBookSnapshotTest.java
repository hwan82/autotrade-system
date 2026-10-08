package com.autotrade.domain.market;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderBookSnapshotTest {

    private static final Instant T = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void 매도호가가_오름차순이_아니면_거부한다() {
        List<PriceLevel> asks = List.of(level("101"), level("100"));
        assertThatThrownBy(() -> new OrderBookSnapshot(Symbol.krx("005930"), 1, T, T, asks, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 매수호가가_내림차순이_아니면_거부한다() {
        List<PriceLevel> bids = List.of(level("99"), level("100"));
        assertThatThrownBy(() -> new OrderBookSnapshot(Symbol.krx("005930"), 1, T, T, List.of(), bids))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PriceLevel level(String price) {
        return new PriceLevel(new BigDecimal(price), 10);
    }
}
