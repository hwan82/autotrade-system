package com.autotrade.broker.kis.order;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 한국투자 주문번호. 취소에는 주문번호({@code ODNO})와 거래소 전송 주문조직번호({@code KRX_FWDG_ORD_ORGNO})가 함께 필요하다
 * `[사실]`(공식 examples_llm/domestic_stock/order_rvsecncl). 비밀값이 아니다.
 *
 * <p>같은 주문인지는 앞자리 0을 뺀 숫자로 비교한다. 주문 응답과 일별 체결 조회가 자릿수를 다르게 줄 수 있다고 보고 `[가정]`
 * 둘 다 받아들인다(Q-015 첫 실주문에서 확인).
 */
public record KisOrderNo(String orgNo, String odno) {

    private static final Pattern ODNO = Pattern.compile("\\d{1,10}");
    private static final Pattern ORG = Pattern.compile("\\d{0,10}");

    public KisOrderNo {
        Objects.requireNonNull(orgNo, "orgNo");
        Objects.requireNonNull(odno, "odno");
        orgNo = orgNo.strip();
        odno = odno.strip();
        if (!ODNO.matcher(odno).matches() || !ORG.matcher(orgNo).matches()) {
            throw new IllegalArgumentException("한국투자 주문번호 형식이 아닙니다");
        }
    }

    /** 앞자리 0을 뺀 주문번호. 비교에 쓴다. */
    public String normalizedOdno() {
        return normalize(odno);
    }

    static String normalize(String digits) {
        String s = digits.replaceFirst("^0+", "");
        return s.isEmpty() ? "0" : s;
    }

    public boolean sameOrder(String otherOdno) {
        return otherOdno != null && ODNO.matcher(otherOdno.strip()).matches()
                && normalize(otherOdno.strip()).equals(normalizedOdno());
    }
}
