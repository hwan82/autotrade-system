package com.autotrade.marketdata.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.SessionPhase;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
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

    @ParameterizedTest
    @CsvSource({
        // 2026-10-12(월) 평일 정규장, KST
        "2026-10-12T08:29:59.999+09:00, CLOSED", "2026-10-12T08:30:00+09:00, OPENING_AUCTION",
        "2026-10-12T08:50:00+09:00, OPENING_AUCTION", "2026-10-12T09:00:00+09:00, CONTINUOUS",
        "2026-10-12T15:19:59.999+09:00, CONTINUOUS", "2026-10-12T15:20:00+09:00, CLOSING_AUCTION",
        "2026-10-12T15:29:59.999+09:00, CLOSING_AUCTION", "2026-10-12T15:30:00+09:00, CLOSED",
        "2026-10-12T15:40:00+09:00, CLOSED",
        // 휴장일(2026-10-10 토요일)
        "2026-10-10T10:00:00+09:00, CLOSED",
        // 수능일 특별 운영 10:00~16:30: 단일가 구간도 같은 길이로 늦춰 잡는다
        "2026-11-19T09:00:00+09:00, CLOSED", "2026-11-19T09:30:00+09:00, OPENING_AUCTION",
        "2026-11-19T10:00:00+09:00, CONTINUOUS", "2026-11-19T16:20:00+09:00, CLOSING_AUCTION",
        "2026-11-19T16:30:00+09:00, CLOSED",
    })
    void KRX_매매_단계는_단일가_구간을_나눈다(String at, SessionPhase phase) {
        Instant instant = java.time.OffsetDateTime.parse(at).toInstant();
        assertThat(krx.sessionPhase(instant)).isEqualTo(phase);
        assertThat(krx.isRegularSession(instant)).isEqualTo(phase == SessionPhase.CONTINUOUS
                || phase == SessionPhase.CLOSING_AUCTION);
    }

    @Test
    void 미국은_단일가_구간을_따로_두지_않는다() {
        Instant open = java.time.OffsetDateTime.parse("2026-10-12T09:30:00-04:00").toInstant();
        assertThat(us.sessionPhase(open)).isEqualTo(SessionPhase.CONTINUOUS);
        assertThat(us.sessionPhase(open.minusSeconds(1))).isEqualTo(SessionPhase.CLOSED);
    }

    @Test
    void KRX만_시가_단일가로_연다() {
        assertThat(krx.opensWithAuction()).isTrue();
        assertThat(us.opensWithAuction()).isFalse();
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

    @Test
    void 하루_가격_제한폭은_KRX_주식만_있다() {
        assertThat(krx.dailyPriceLimit(Symbol.krx("005930"))).contains(new BigDecimal("0.30"));
        // ETF·ETN은 레버리지 배율을 몰라 비어 있고, 미국은 시장 단위 제한폭이 없다(D-084)
        assertThat(krx.dailyPriceLimit(ETF)).isEqualTo(Optional.empty());
        assertThat(us.dailyPriceLimit(Symbol.us("AAPL"))).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> krx.dailyPriceLimit(Symbol.us("AAPL"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 번들_규칙은_시장마다_그_시장_구현이다() {
        for (Market m : Market.values()) {
            assertThat(BundledMarketRules.of(m).market()).isEqualTo(m);
        }
        assertThat(BundledMarketRules.of(Market.KRX).dailyPriceLimit(ETF)).contains(new BigDecimal("0.30"));
    }
}
