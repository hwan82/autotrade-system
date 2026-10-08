package com.autotrade.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void 스케일이_달라도_같은_금액이면_같다() {
        assertThat(Money.of("1000", Money.KRW)).isEqualTo(Money.of("1000.00", Money.KRW));
        assertThat(Money.of("0.000", Money.USD)).isEqualTo(Money.zero(Money.USD));
    }

    @Test
    void 더하기_빼기_곱하기는_손계산과_일치한다() {
        Money price = Money.of("71500", Money.KRW);
        assertThat(price.times(13)).isEqualTo(Money.of("929500", Money.KRW));
        assertThat(Money.of("0.1", Money.USD).plus(Money.of("0.2", Money.USD))).isEqualTo(Money.of("0.3", Money.USD));
        assertThat(Money.of("100", Money.KRW).minus(Money.of("250", Money.KRW)).signum()).isNegative();
    }

    @Test
    void 배율_곱셈은_정확하고_반올림은_명시해야_한다() {
        Money fee = Money.of("929500", Money.KRW).times(new BigDecimal("0.00015"));
        assertThat(fee).isEqualTo(Money.of("139.425", Money.KRW));
        assertThat(fee.roundedToCurrency(RoundingMode.DOWN)).isEqualTo(Money.of("139", Money.KRW));
        assertThat(Money.of("1.005", Money.USD).roundedToCurrency(RoundingMode.HALF_UP)).isEqualTo(Money.of("1.01", Money.USD));
    }

    @Test
    void 통화가_다르면_계산을_거부한다() {
        assertThatThrownBy(() -> Money.of(1, Money.KRW).plus(Money.of(1, Money.USD)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, Money.KRW).compareTo(Money.of(1, Currency.getInstance("JPY"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 문자열은_지수표기_없이_통화와_함께_나온다() {
        assertThat(Money.of("1000000", Money.KRW)).hasToString("1000000 KRW");
    }
}
