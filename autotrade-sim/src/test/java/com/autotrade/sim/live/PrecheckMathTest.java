package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 사전 검사 정확 산술(ADR-014 5.3): Clopper–Pearson 상한과 LIVE02 시드 수 표의 고정값(계획 6.2 표와 같음). */
class PrecheckMathTest {

    @Test
    void liveSeedsMatchTheFixedTableOfPlanSectionSixTwo() {
        String[] q = {"0", "0.05", "0.1", "0.3"};
        int[] m4 = {83, 89, 96, 130};
        int[] m2 = {41, 45, 49, 68};
        // m = 1·3(PR #210 strategy-researcher R1, 정확 이항으로 다시 센 값)
        int[] m1 = {20, 23, 25, 36};
        int[] m3 = {62, 67, 73, 100};
        for (int i = 0; i < q.length; i++) {
            assertThat(PrecheckMath.liveSeeds(new BigDecimal(q[i]), 4, 999)).as("q=" + q[i] + ", m=4")
                    .isEqualTo(OptionalInt.of(m4[i]));
            assertThat(PrecheckMath.liveSeeds(new BigDecimal(q[i]), 2, 999)).as("q=" + q[i] + ", m=2")
                    .isEqualTo(OptionalInt.of(m2[i]));
            assertThat(PrecheckMath.liveSeeds(new BigDecimal(q[i]), 1, 999)).as("q=" + q[i] + ", m=1")
                    .isEqualTo(OptionalInt.of(m1[i]));
            assertThat(PrecheckMath.liveSeeds(new BigDecimal(q[i]), 3, 999)).as("q=" + q[i] + ", m=3")
                    .isEqualTo(OptionalInt.of(m3[i]));
        }
        // q가 1에 가까우면 999 안에서 끝나지 않는다(R1)
        assertThat(PrecheckMath.liveSeeds(new BigDecimal("0.9"), 4, 999)).isEmpty();
    }

    @Test
    void clopperPearsonUpperBoundIsExactAndRoundedUp() {
        // x = 0이면 1 − 0.05^(1/N): N = 199 → 0.014941… → 올림 0.0150, N = 83 → 0.0354…
        assertThat(PrecheckMath.display(PrecheckMath.upperBound(0, 199))).isEqualTo("0.0150");
        BigDecimal u83 = PrecheckMath.upperBound(0, 83);
        assertThat(PrecheckMath.display(u83)).isEqualTo("0.0355");
        assertThat(u83).isBetween(new BigDecimal("0.0354"), new BigDecimal("0.0355"));
        // 상한이 0이 아니라 m = 4에서 N = 87이 된다(ADR-014 5.3)
        assertThat(PrecheckMath.liveSeeds(u83, 4, 999)).isEqualTo(OptionalInt.of(87));
        assertThat(PrecheckMath.upperBound(5, 5)).isEqualTo(BigDecimal.ONE);
        // 상한에서 누적 꼬리는 0.05 이하, 1e-9 아래쪽은 0.05 초과(위쪽 끝을 냄)
        BigDecimal u = PrecheckMath.upperBound(3, 40);
        assertThat(PrecheckMath.binomialCdf(40, 3, u)).isLessThanOrEqualTo(new BigDecimal("0.05"));
        assertThat(PrecheckMath.binomialCdf(40, 3, u.subtract(new BigDecimal("1E-9")))).isGreaterThan(new BigDecimal("0.05"));
        // 같은 입력이면 같은 값(정확 산술)
        assertThat(PrecheckMath.upperBound(3, 40)).isEqualTo(u);
    }

    @Test
    void binomialCdfEdges() {
        assertThat(PrecheckMath.binomialCdf(10, 10, new BigDecimal("0.3"))).isEqualTo(BigDecimal.ONE);
        assertThat(PrecheckMath.binomialCdf(10, 0, BigDecimal.ZERO).compareTo(BigDecimal.ONE)).isZero();
        assertThat(PrecheckMath.binomialCdf(10, 9, BigDecimal.ONE).signum()).isZero();
        // P(Bin(2, 0.5) ≤ 1) = 0.75
        assertThat(PrecheckMath.binomialCdf(2, 1, new BigDecimal("0.5")).compareTo(new BigDecimal("0.75"))).isZero();
    }
}
