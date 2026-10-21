package com.autotrade.sim.live;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.OptionalInt;

/**
 * 녹화 사전 검사의 대조군 품질 계산(ADR-014 5.3): 왕복 0건 시드 비율의 단측 95% Clopper–Pearson 상한과 LIVE02 대조군 시드 수 표.
 *
 * <p>모두 정확 산술이다: 이항 누적은 정수 이항계수({@link BigInteger})와 {@link BigDecimal} 거듭제곱(유효숫자 34자리,
 * {@link MathContext#DECIMAL128})의 합이고, 상한은 이분법으로 {@value #TOLERANCE_TEXT}까지 좁힌다. {@code Math.pow}·{@code exp}·
 * {@code log}는 플랫폼마다 끝자리가 다를 수 있어 쓰지 않는다(architect M1·strategy-researcher R3). 같은 입력이면 어느 PC에서나 같은
 * 값이다.
 */
final class PrecheckMath {

    static final MathContext MC = MathContext.DECIMAL128;
    static final String TOLERANCE_TEXT = "1E-9";
    private static final BigDecimal TOLERANCE = new BigDecimal(TOLERANCE_TEXT);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    /** 단측 95% 상한의 꼬리 확률. */
    static final BigDecimal UPPER_TAIL = new BigDecimal("0.05");
    /** LIVE02 N 표: 판정 가능 시드가 모자랄 확률 상한(계획 6.2 3번). */
    static final BigDecimal SHORT_RISK = new BigDecimal("0.01");
    /** N 표가 셀 판정 대상 수 m. */
    static final int MAX_M = 4;

    private PrecheckMath() {
    }

    /** P(이항(n, p) ≤ x). x ≥ n이면 1. */
    static BigDecimal binomialCdf(int n, int x, BigDecimal p) {
        if (n < 0 || x < 0 || p.signum() < 0 || p.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("n·x는 0 이상, p는 0~1이어야 합니다: n=" + n + ", x=" + x + ", p=" + p);
        }
        if (x >= n) {
            return BigDecimal.ONE;
        }
        BigDecimal q = BigDecimal.ONE.subtract(p);
        BigDecimal sum = BigDecimal.ZERO;
        BigInteger c = BigInteger.ONE;
        for (int i = 0; i <= x; i++) {
            if (i > 0) {
                c = c.multiply(BigInteger.valueOf(n - i + 1)).divide(BigInteger.valueOf(i));
            }
            BigDecimal term = new BigDecimal(c).multiply(p.pow(i, MC), MC).multiply(q.pow(n - i, MC), MC);
            sum = sum.add(term, MC);
        }
        return sum;
    }

    /**
     * 왕복 0건 시드 x개 / 시드 n개에서 왕복 0건 확률의 단측 95% Clopper–Pearson 상한(반올림 전). x = n이면 1, 아니면
     * P(이항(n, q) ≤ x) = 0.05인 q를 이분법으로 좁혀 위쪽 끝을 낸다(상한을 낮춰 잡지 않으려고).
     */
    static BigDecimal upperBound(int x, int n) {
        if (n < 1 || x < 0 || x > n) {
            throw new IllegalArgumentException("0 ≤ x ≤ n, n ≥ 1이어야 합니다: x=" + x + ", n=" + n);
        }
        if (x == n) {
            return BigDecimal.ONE;
        }
        BigDecimal lo = BigDecimal.ZERO;
        BigDecimal hi = BigDecimal.ONE;
        while (hi.subtract(lo).compareTo(TOLERANCE) > 0) {
            BigDecimal mid = lo.add(hi).multiply(HALF);
            // 누적은 q에 대해 줄어든다. 꼬리가 아직 0.05보다 크면 상한은 더 위에 있다
            if (binomialCdf(n, x, mid).compareTo(UPPER_TAIL) > 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    /** 결과 파일 표시: 소수 4자리 올림(상한을 낮춰 보이지 않게, strategy-researcher R4). */
    static String display(BigDecimal upper) {
        return upper.setScale(4, RoundingMode.CEILING).toPlainString();
    }

    /**
     * LIVE02 판정 대상 m개 묶음의 대조군 시드 수: N ≥ 21m − 1 중 P(이항(N, 1 − q) ≤ 20m − 2) ≤ 0.01인 가장 작은 N(쓸 수 있는 시드가
     * 판정 가능 최소 20m − 1보다 모자랄 확률이 1% 이하, 계획 6.2 3번). {@code max}를 넘으면 빈 값("999 초과").
     *
     * @param q 왕복 0건 확률(상한, 반올림 전)
     */
    static OptionalInt liveSeeds(BigDecimal q, int m, int max) {
        BigDecimal usable = BigDecimal.ONE.subtract(q);
        int need = 20 * m - 1;
        for (int n = JudgeRoles.defaultSeeds(m); n <= max; n++) {
            if (binomialCdf(n, need - 1, usable).compareTo(SHORT_RISK) <= 0) {
                return OptionalInt.of(n);
            }
        }
        return OptionalInt.empty();
    }
}
