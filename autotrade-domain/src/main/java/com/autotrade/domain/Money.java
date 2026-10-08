package com.autotrade.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 통화가 붙은 금액. double 금액 계산을 막기 위해 금액은 항상 이 타입으로 주고받는다.
 *
 * <p>값 동등성은 스케일과 무관하다(1000 == 1000.00). 내부 금액은 trailing zero를 제거해 정규화한다.
 * 반올림은 호출자가 {@link MarketRules} 또는 비용 모델에서 명시적으로 한다.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final Currency KRW = Currency.getInstance("KRW");
    public static final Currency USD = Currency.getInstance("USD");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = normalize(amount);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(long amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money times(long quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    /** 배율 곱셈(수수료율 등). 정확한 값이며 반올림하지 않는다. 반올림은 {@link #rounded}로 명시한다. */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        return new Money(amount.multiply(factor), currency);
    }

    /** 소수 {@code scale} 자리로 반올림. 비용 모델은 반올림 위치와 방향을 여기로만 정한다. */
    public Money rounded(int scale, RoundingMode mode) {
        return new Money(amount.setScale(scale, mode), currency);
    }

    /** 통화의 기본 소수 자릿수(KRW 0, USD 2)로 반올림. */
    public Money roundedToCurrency(RoundingMode mode) {
        return rounded(currency.getDefaultFractionDigits(), mode);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public int signum() {
        return amount.signum();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("통화가 다른 금액은 계산할 수 없습니다: " + this + ", " + other);
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
}
