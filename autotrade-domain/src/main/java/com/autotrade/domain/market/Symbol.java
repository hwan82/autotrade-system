package com.autotrade.domain.market;

import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 시장과 종목 코드. 코드에는 영문 대문자, 숫자, 점만 허용한다.
 * '-'는 멱등 키 구분자이므로 쓸 수 없다(예: 미국 BRK-B는 BRK.B로 표기).
 */
public record Symbol(Market market, String code) implements Comparable<Symbol> {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9.]{1,12}");
    private static final Comparator<Symbol> ORDER =
            Comparator.comparing(Symbol::market).thenComparing(Symbol::code);

    public Symbol {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("종목 코드 형식이 아닙니다: " + code);
        }
    }

    public static Symbol krx(String code) {
        return new Symbol(Market.KRX, code);
    }

    public static Symbol us(String code) {
        return new Symbol(Market.US, code);
    }

    @Override
    public int compareTo(Symbol other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return market + ":" + code;
    }
}
