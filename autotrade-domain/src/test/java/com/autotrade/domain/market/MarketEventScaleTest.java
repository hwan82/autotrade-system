package com.autotrade.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 녹화 파일 왕복으로 가격 스케일이 바뀌어도 리플레이 비교가 같다고 판단해야 한다. */
class MarketEventScaleTest {

    private static final Instant T = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void 체결가_스케일이_달라도_같은_이벤트다() {
        TradeTick a = new TradeTick(Symbol.krx("005930"), 1, T, T, new BigDecimal("71500"), 10);
        TradeTick b = new TradeTick(Symbol.krx("005930"), 1, T, T, new BigDecimal("71500.00"), 10);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void 호가_스케일이_달라도_같은_호가다() {
        assertThat(new PriceLevel(new BigDecimal("1.50"), 3)).isEqualTo(new PriceLevel(new BigDecimal("1.5"), 3));
    }
}
