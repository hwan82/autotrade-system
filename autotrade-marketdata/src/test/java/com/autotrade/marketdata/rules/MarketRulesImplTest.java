package com.autotrade.marketdata.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MarketRulesImplTest {

    private static final Symbol ETF = Symbol.krx("069500");
    private final KrxMarketRules krx = new KrxMarketRules(ExchangeCalendar.bundled(Market.KRX),
            s -> s.equals(ETF) ? InstrumentKind.ETF : InstrumentKind.STOCK);
    private final UsMarketRules us = new UsMarketRules(ExchangeCalendar.bundled(Market.US));

    @ParameterizedTest
    @CsvSource({
        "1999, 1", "2000, 5", "4995, 5", "5000, 10", "19990, 10", "20000, 50", "49950, 50",
        "50000, 100", "199900, 100", "200000, 500", "499500, 500", "500000, 1000", "1500000, 1000",
    })
    void KRX_주식_호가_단위(String price, String tick) {
        assertThat(krx.tickSize(Symbol.krx("005930"), new BigDecimal(price))).isEqualByComparingTo(tick);
    }

    @Test
    void KRX_ETF는_5원_단위다() {
        assertThat(krx.tickSize(ETF, new BigDecimal("35000"))).isEqualByComparingTo("5");
        assertThat(krx.roundToTick(ETF, new BigDecimal("35003"), RoundingMode.DOWN)).isEqualByComparingTo("35000");
    }

    @Test
    void KRX_가격을_호가_단위로_맞춘다() {
        assertThat(krx.roundToTick(Symbol.krx("005930"), new BigDecimal("71530"), RoundingMode.DOWN)).isEqualByComparingTo("71500");
        assertThat(krx.roundToTick(Symbol.krx("005930"), new BigDecimal("71530"), RoundingMode.UP)).isEqualByComparingTo("71600");
    }

    @Test
    void 미국_호가_단위는_1달러를_경계로_나뉜다() {
        assertThat(us.tickSize(Symbol.us("AAPL"), new BigDecimal("198.5"))).isEqualByComparingTo("0.01");
        assertThat(us.tickSize(Symbol.us("SNDL"), new BigDecimal("0.8765"))).isEqualByComparingTo("0.0001");
        assertThat(us.tickSize(Symbol.us("X"), BigDecimal.ONE)).isEqualByComparingTo("0.01");
    }

    @Test
    void 시장이_다른_종목은_거절한다() {
        assertThatThrownBy(() -> krx.tickSize(Symbol.us("AAPL"), BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> us.tickSize(Symbol.krx("005930"), BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거래일과_시간대는_시장_현지_기준이다() {
        assertThat(krx.zone().getId()).isEqualTo("Asia/Seoul");
        assertThat(us.tradingDate(Instant.parse("2026-10-09T02:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(krx.isRegularSession(Instant.parse("2026-10-08T01:00:00Z"))).isTrue();
        assertThat(us.isRegularSession(Instant.parse("2026-10-08T01:00:00Z"))).isFalse();
    }
}
