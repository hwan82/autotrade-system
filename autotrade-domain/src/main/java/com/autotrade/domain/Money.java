package com.autotrade.domain;

import java.math.BigDecimal;
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
