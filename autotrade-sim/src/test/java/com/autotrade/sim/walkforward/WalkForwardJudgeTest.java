package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.sim.study.Check;
import com.autotrade.sim.study.Check.Outcome;
import com.autotrade.sim.study.ScreeningCriteria;
import com.autotrade.sim.walkforward.WalkForwardJudge.Verdict;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 판정 경계값(4단계 계획 4장): 구간 3/4 올림, C10 표본 하한과 p 경계, C3 경계, 판정 합성 순서. */
class WalkForwardJudgeTest {

    private static Check check(String code, Outcome o) {
        return new Check(code, code, o, "");
    }

    private static List<BigDecimal> nulls(int n) {
        List<BigDecimal> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            l.add(BigDecimal.valueOf(i * 100L)); // 100, 200, ..., n*100
        }
        return l;
    }

    @Test
    void 구간의_3분의_4_올림_기준() {
        assertThat(WalkForwardJudge.requiredWindows(3)).isEqualTo(3);
        assertThat(WalkForwardJudge.requiredWindows(4)).isEqualTo(3);
        assertThat(WalkForwardJudge.requiredWindows(5)).isEqualTo(4);
        assertThat(WalkForwardJudge.requiredWindows(8)).isEqualTo(6);
    }

    @Test
    void C10_표본_하한은_N_더하기_1이_20m_이상() {
        assertThat(WalkForwardJudge.c10Possible(19, 1)).isTrue(); // N+1 = 20
        assertThat(WalkForwardJudge.c10Possible(18, 1)).isFalse();
        assertThat(WalkForwardJudge.c10Possible(199, 10)).isTrue(); // 200
        assertThat(WalkForwardJudge.c10Possible(198, 10)).isFalse();
        assertThat(WalkForwardJudge.nullQuantile(10)).isEqualByComparingTo("0.995");
    }

    @Test
    void 경험적_p는_k_더하기_1_나누기_N_더하기_1이고_같은_값은_이상으로_센다() {
        List<BigDecimal> n = nulls(19); // N+1 = 20, m = 1 → 통과하려면 k = 0
        // 후보가 최대(1900)보다 크면 k=0 → p = 1/20 = 0.05
        assertThat(WalkForwardJudge.pValue(new BigDecimal("1901"), n, 1)).isEqualByComparingTo("0.05");
        // 후보가 최대와 같으면 같은 값도 이상이라 k=1 → p = 0.1
        assertThat(WalkForwardJudge.pValue(new BigDecimal("1900"), n, 1)).isEqualByComparingTo("0.1");
        // 표본 부족이면 p 없음
        assertThat(WalkForwardJudge.pValue(new BigDecimal("1901"), nulls(18), 1)).isNull();
        // 왕복 0이면 p 없음
        assertThat(WalkForwardJudge.pValue(null, n, 1)).isNull();
    }

    @Test
    void C3_MDD는_20퍼센트_정확히면_통과하고_넘으면_탈락() {
        BigDecimal limit = new BigDecimal("0.20");
        Check at = ScreeningCriteria.threshold("C3", "MDD", new BigDecimal("0.20"), limit, false, "20%");
        Check over = ScreeningCriteria.threshold("C3", "MDD", new BigDecimal("0.2001"), limit, false, "20.01%");
        assertThat(at.outcome()).isEqualTo(Outcome.PASS);
        assertThat(over.outcome()).isEqualTo(Outcome.FAIL);
    }

    @Test
    void 판정_합성_C5_미달은_다른_탈락보다_먼저_보류() {
        List<Check> c = List.of(check("C1", Outcome.FAIL), check("C5", Outcome.NOT_AVAILABLE));
        assertThat(WalkForwardJudge.verdict(c)).isEqualTo(Verdict.HOLD);
    }

    @Test
    void 판정_합성_탈락_보류_통과_순서() {
        assertThat(WalkForwardJudge.verdict(List.of(check("C1", Outcome.FAIL), check("C5", Outcome.PASS),
                check("C10", Outcome.NOT_AVAILABLE)))).isEqualTo(Verdict.FAIL);
        assertThat(WalkForwardJudge.verdict(List.of(check("C1", Outcome.PASS), check("C5", Outcome.PASS),
                check("C10", Outcome.NOT_AVAILABLE)))).isEqualTo(Verdict.HOLD);
        assertThat(WalkForwardJudge.verdict(List.of(check("C1", Outcome.PASS), check("C5", Outcome.PASS))))
                .isEqualTo(Verdict.PASS);
    }
}
