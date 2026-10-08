package com.autotrade.sim.metrics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 지표 계산용 BigDecimal 로그·지수. double을 거치지 않아 플랫폼과 무관하게 같은 값이 나온다(결정론, ARC-08).
 * 정밀도 {@link #MC}(40자리)로 계산하고 지표는 소수 6자리로 내보내므로 반올림 차이가 결과에 남지 않는다.
 */
final class DecimalMath {

    static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);
    private static final BigDecimal REDUCE = new BigDecimal("0.001");
    private static final BigDecimal EPS = new BigDecimal("1e-45");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private DecimalMath() {
    }

    /** 자연로그. x > 0. 제곱근으로 1 가까이 줄인 뒤 2·atanh 급수. */
    static BigDecimal ln(BigDecimal x) {
        if (x.signum() <= 0) {
            throw new ArithmeticException("ln의 인자는 0보다 커야 합니다: " + x);
        }
        int k = 0;
        BigDecimal v = x;
        while (v.subtract(BigDecimal.ONE).abs().compareTo(REDUCE) > 0) {
            v = v.sqrt(MC);
            k++;
        }
        BigDecimal y = v.subtract(BigDecimal.ONE).divide(v.add(BigDecimal.ONE), MC);
        BigDecimal y2 = y.multiply(y, MC);
        BigDecimal term = y;
        BigDecimal sum = BigDecimal.ZERO;
        for (int n = 1; term.abs().compareTo(EPS) > 0; n += 2) {
            sum = sum.add(term.divide(BigDecimal.valueOf(n), MC), MC);
            term = term.multiply(y2, MC);
        }
        return sum.multiply(TWO).multiply(TWO.pow(k), MC);
    }

    /** 지수 함수. 인자를 반으로 줄인 뒤 테일러 급수, 다시 제곱. */
    static BigDecimal exp(BigDecimal z) {
        int k = 0;
        BigDecimal v = z;
        while (v.abs().compareTo(REDUCE) > 0) {
            v = v.divide(TWO, MC);
            k++;
        }
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (int n = 1; term.abs().compareTo(EPS) > 0; n++) {
            term = term.multiply(v, MC).divide(BigDecimal.valueOf(n), MC);
            sum = sum.add(term, MC);
        }
        for (int i = 0; i < k; i++) {
            sum = sum.multiply(sum, MC);
        }
        return sum;
    }
}
