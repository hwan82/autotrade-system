package com.autotrade.broker.kis.order;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국투자 계좌번호. 종합계좌번호 8자리({@code CANO})와 상품코드 2자리({@code ACNT_PRDT_CD})로 나눠 보낸다
 * `[사실]`(공식 open-trading-api examples_llm/domestic_stock/inquire_balance "계좌번호 체계(8-2)").
 *
 * <p>비밀값이다(안전 불변식 9). {@link #toString()}은 값을 가리고, 형식 오류 메시지에도 값을 넣지 않는다.
 * 실전 엔진이 {@code secrets.json}의 {@code real-trading.account-numbers.<id>}에서 꺼내 넘긴다.
 */
public final class KisAccountNumber {

    /** "12345678-01" 또는 "1234567801"(숫자 10자리). 자리 표시자 예: {@code <8자리>-<2자리>}. */
    private static final Pattern FORMAT = Pattern.compile("(\\d{8})-?(\\d{2})");

    private final String cano;
    private final String productCode;

    private KisAccountNumber(String cano, String productCode) {
        this.cano = cano;
        this.productCode = productCode;
    }

    public static KisAccountNumber parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher m = FORMAT.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("한국투자 계좌번호 형식이 아닙니다(숫자 8자리-2자리)");
        }
        return new KisAccountNumber(m.group(1), m.group(2));
    }

    String cano() {
        return cano;
    }

    String productCode() {
        return productCode;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KisAccountNumber other && cano.equals(other.cano) && productCode.equals(other.productCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cano, productCode);
    }

    @Override
    public String toString() {
        return "KisAccountNumber[****]";
    }
}
