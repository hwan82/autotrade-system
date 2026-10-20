package com.autotrade.sim.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 판정 규칙(D-125 2장) 손 계산. 하드 필터 통과 40종목, 종목 j의 변동폭 = j ÷ 100, 선정 = 위 6종목(34~39). 판정 구간 3개에 이틀씩.
 */
class EdgeJudgeTest {

    private static final int H = 40;

    private static EdgePlan plan(String test, int seeds) {
        return plan(test, seeds, 1);
    }

    private static EdgePlan plan(String test, int seeds, int m) {
        return EdgePlan.parse("""
                id: "EXP-20261020-EDGET"
                question: "q"
                market: "KRX"
                bars-snapshot: "krx-daily-test"
                selection: "test-edge-hot@1"
                test: "%s"
                family-size: "%d"
                seed: "7"
                null-seeds: "%d"
                trade-notional: "1000000"
                holdout-from: "2026-04-01"
                parallelism: "1"
                windows:
                  - "2026-01-05..2026-01-31"
                  - "2026-02-01..2026-02-28"
                  - "2026-03-01..2026-03-31"
                """.formatted(test, m, seeds), "t");
    }

    /** 순방향이면 net = range − 0.1, 역방향이면 net = 0.1 − range. */
    private static List<SelectionEdgeStudy.Day> days(boolean netFollowsRange, int k) {
        List<SelectionEdgeStudy.Day> out = new ArrayList<>();
        LocalDate[] ts = {LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7), LocalDate.of(2026, 2, 3),
            LocalDate.of(2026, 2, 4), LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 4)};
        for (int d = 0; d < ts.length; d++) {
            double[] range = new double[H];
            double[] net = new double[H];
            for (int j = 0; j < H; j++) {
                range[j] = j / 100.0;
                net[j] = netFollowsRange ? range[j] - 0.1 : 0.1 - range[j];
            }
            int[] selected = new int[k];
            for (int i = 0; i < k; i++) {
                selected[i] = H - 1 - i;
            }
            out.add(new SelectionEdgeStudy.Day(ts[d], ts[d].minusDays(1), d / 2, selected, range, net, new boolean[H],
                    k + 3, Map.of()));
        }
        return out;
    }

    @Test
    void 변동폭이_가장_큰_선정은_통과() {
        EdgeJudge.Result r = EdgeJudge.judge(plan("RANGE", 39), days(true, 6));
        assertThat(r.all().range().n()).isEqualTo(36);
        assertThat(r.all().range().value()).isCloseTo((34 + 35 + 36 + 37 + 38 + 39) / 600.0, within(1e-12));
        // 무작위 6종목 평균은 위 6종목 평균을 넘을 수 없어 p = 1 ÷ (39 + 1)
        assertThat(r.pHigh()).isEqualTo(1.0 / 40);
        assertThat(r.positiveWindows()).isEqualTo(3);
        assertThat(r.verdict()).isEqualTo(EdgeJudge.Verdict.PASS);
        assertThat(r.all().cappedDays()).as("통과 9 > 선정 6인 날").isEqualTo(6);
    }

    @Test
    void 변동폭이_커도_방향이_유의하게_나쁘면_E3으로_탈락() {
        EdgeJudge.Result r = EdgeJudge.judge(plan("RANGE", 39), days(false, 6));
        assertThat(r.pHigh()).isEqualTo(1.0 / 40);
        assertThat(r.pNetLow()).isEqualTo(1.0 / 40);
        assertThat(r.verdict()).isEqualTo(EdgeJudge.Verdict.FAIL);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("E3 미달"));
    }

    @Test
    void E3는_본페로니_보정_없이_0_05를_쓴다() {
        // m = 3, N = 59: E1 α = 0.0167, E3 기준은 0.05. 역방향이면 net 아래쪽 p = 1/60
        EdgeJudge.Result r = EdgeJudge.judge(plan("RANGE", 59, 3), days(false, 6));
        assertThat(r.alpha()).isEqualTo(0.05 / 3);
        assertThat(r.pNetLow()).isEqualTo(1.0 / 60);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("E3 미달") && s.contains("≤ 0.0500(보정 없음)"));
        assertThat(EdgeJudge.E3_ALPHA).isEqualTo(0.05);
        // (0.05/m, 0.05] 사이 p는 보정 없는 기준으로 미달(α/m이었다면 통과), 0.05 초과만 통과
        assertThat(EdgeJudge.e3Passes(EdgePlan.Test.RANGE, 2.0 / 60)).isFalse();
        assertThat(EdgeJudge.e3Passes(EdgePlan.Test.RANGE, 3.0 / 60)).isFalse();
        assertThat(EdgeJudge.e3Passes(EdgePlan.Test.RANGE, 4.0 / 60)).isTrue();
        assertThat(EdgeJudge.e3Passes(EdgePlan.Test.NET, 1.0 / 60)).as("NET이면 E3를 보지 않음").isTrue();
    }

    @Test
    void 주_통계가_NET이면_E3은_보지_않는다() {
        EdgeJudge.Result r = EdgeJudge.judge(plan("NET", 39), days(true, 6));
        assertThat(r.verdict()).isEqualTo(EdgeJudge.Verdict.PASS);
        assertThat(r.reasons()).noneMatch(s -> s.startsWith("E3"));
        assertThat(EdgeJudge.judge(plan("NET", 39), days(false, 6)).verdict()).isEqualTo(EdgeJudge.Verdict.FAIL);
    }

    @Test
    void 후보_왕복이_30건_미만이면_판정_불가() {
        EdgeJudge.Result r = EdgeJudge.judge(plan("RANGE", 39), days(true, 4)); // 24건
        assertThat(r.verdict()).isEqualTo(EdgeJudge.Verdict.UNDECIDED);
        assertThat(r.reasons()).anyMatch(s -> s.contains("24건"));
    }

    @Test
    void 거래_불가_선정은_후보와_대조군_모두에서_빠진다() {
        List<SelectionEdgeStudy.Day> ds = days(true, 6);
        for (SelectionEdgeStudy.Day d : ds) {
            d.range()[39] = Double.NaN;
            d.net()[39] = Double.NaN;
        }
        EdgeJudge.Result r = EdgeJudge.judge(plan("RANGE", 39), ds);
        assertThat(r.all().range().n()).isEqualTo(30);
        assertThat(r.all().untradableSelected()).isEqualTo(6);
        assertThat(r.all().range().value()).isCloseTo((34 + 35 + 36 + 37 + 38) / 500.0, within(1e-12));
    }

    @Test
    void 생존편향_미확인이면_판정_불가() {
        EdgeSurvivorship.Result sv = new EdgeSurvivorship.Result(new java.util.TreeSet<>(List.of("B")), 40, 0, 0.3, 500,
                0, 0.5, false);
        EdgeJudge.Result r = EdgeJudge.judge(plan("NET", 39), days(true, 6), sv);
        assertThat(r.verdict()).isEqualTo(EdgeJudge.Verdict.UNDECIDED);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("생존편향 미확인"));
    }

    @Test
    void p값_중앙값_분위수() {
        double[] c = {0.1, Double.NaN, 0.3, 0.2, 0.4};
        assertThat(EdgeJudge.pUpper(c, 0.3)).isEqualTo(3.0 / 5); // 0.3·0.4 두 개 + 1, N′ = 4
        assertThat(EdgeJudge.pLower(c, 0.1)).isEqualTo(2.0 / 5);
        assertThat(EdgeJudge.median(c)).isCloseTo(0.25, within(1e-12));
        assertThat(EdgeJudge.quantile(c, 0.8)).isEqualTo(0.4); // ⌈0.8 × 5⌉ = 4번째
        assertThat(EdgeJudge.pUpper(c, Double.NaN)).isEqualTo(1.0);
    }

    @Test
    void 대조군_표본은_시드와_날짜로만_정해지고_중복이_없다() {
        LocalDate t = LocalDate.of(2026, 3, 3);
        int[] a = SelectionEdgeStudy.sample(7, 3, t, 50, 20);
        int[] b = SelectionEdgeStudy.sample(7, 3, t, 50, 20);
        assertThat(a).containsExactly(b);
        assertThat(java.util.Arrays.stream(a).distinct().count()).isEqualTo(20);
        assertThat(java.util.Arrays.stream(a).allMatch(v -> v >= 0 && v < 50)).isTrue();
        assertThat(SelectionEdgeStudy.sample(7, 4, t, 50, 20)).isNotEqualTo(a);
        assertThat(SelectionEdgeStudy.sample(7, 3, t.plusDays(1), 50, 20)).isNotEqualTo(a);
        assertThat(SelectionEdgeStudy.sample(7, 0, t, 5, 5)).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
    }
}
