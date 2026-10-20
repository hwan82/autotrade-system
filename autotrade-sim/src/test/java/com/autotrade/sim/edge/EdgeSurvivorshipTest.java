package com.autotrade.sim.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 생존편향 확인 규칙(D-125 2장 5번, PR #198 strategy-researcher n3) 손 계산. */
class EdgeSurvivorshipTest {

    @Test
    void 폐지_전_마지막_365일에_걸친_연도는_세지_않는다() {
        // 일봉 2014-01-02 ~ 2017-03-31 → 끝 = 2016-03-31. 2014·2015만 셀 수 있음(2016은 12월이 끝 뒤)
        var cov = EdgeSurvivorship.coverage(LocalDate.of(2014, 1, 2), LocalDate.of(2017, 3, 31), y -> true,
                y -> y == 2016);
        // 2014는 1월 1일이 첫 일봉 전이라 빠짐 → 2015만 셈, 2016 공시는 안 셈
        assertThat(cov.getAsDouble()).isEqualTo(0.0);
        var cov2 = EdgeSurvivorship.coverage(LocalDate.of(2013, 12, 30), LocalDate.of(2017, 3, 31), y -> true,
                y -> y == 2014 || y == 2016);
        assertThat(cov2.getAsDouble()).isCloseTo(0.5, within(1e-12)); // 2014 있음, 2015 없음 → 1/2
    }

    @Test
    void 공시_스냅샷이_덮지_않는_연도는_세지_않는다() {
        var cov = EdgeSurvivorship.coverage(LocalDate.of(2010, 1, 4), LocalDate.of(2020, 12, 30),
                y -> y >= 2016, y -> y == 2017);
        // 셀 수 있는 연도 2016~2019(끝 2019-12-31) 4개 중 2017 하나
        assertThat(cov.getAsDouble()).isCloseTo(0.25, within(1e-12));
    }

    @Test
    void 셀_연도가_없으면_비어_있다() {
        assertThat(EdgeSurvivorship.coverage(LocalDate.of(2015, 3, 2), LocalDate.of(2016, 6, 30), y -> true, y -> true))
                .isEmpty();
        assertThat(EdgeSurvivorship.coverage(LocalDate.of(2010, 1, 4), LocalDate.of(2020, 12, 30), y -> false,
                y -> true)).isEmpty();
    }

    @Test
    void 통과는_폐지_30개_이상이고_비율_0_8_이상() {
        assertThat(EdgeSurvivorship.passes(30, 0.4, 500, 0.5)).as("정확히 0.8배, 30개").isTrue();
        assertThat(EdgeSurvivorship.passes(29, 0.5, 500, 0.5)).as("폐지 29개").isFalse();
        assertThat(EdgeSurvivorship.passes(30, 0.3999, 500, 0.5)).isFalse();
        assertThat(EdgeSurvivorship.passes(30, 0.5, 0, Double.NaN)).as("유지 종목 없음").isFalse();
        assertThat(EdgeSurvivorship.passes(0, Double.NaN, 500, 0.5)).isFalse();
    }

    @Test
    void 결과는_유형을_정렬해_보관한다() {
        var r = new EdgeSurvivorship.Result(new java.util.TreeSet<>(Set.of("I", "B")), 30, 1, 0.4, 500, 2, 0.5, true);
        assertThat(r.types()).containsExactly("B", "I");
        assertThat(r.ratio()).isCloseTo(0.8, within(1e-12));
    }
}
