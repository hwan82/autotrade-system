package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.strategy.catalog.EntryFunnel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 검증 리더보드의 비용 비중·회전율 손 계산(PR #98 strategy-researcher 검토 M1: 구간 원 금액 배율로 분자·분모를 맞춘다). */
class PooledCostShareTest {

    private static final Money SEED = Money.of(10_000_000, Money.KRW);

    private static WindowRun window(int k, long finalEquity, long costs, String turnover) {
        RunMetrics m = new RunMetrics(Money.of(finalEquity, Money.KRW), BigDecimal.ZERO, null, BigDecimal.ZERO, null, null,
                null, null, 0, 0, new BigDecimal(turnover), Money.of(costs, Money.KRW), null, null, null);
        return new WindowRun(k, "", m, List.of(), List.of(), List.of(), Set.of(), EntryFunnel.Totals.ZERO, ExitMix.ZERO);
    }

    @Test
    void 비용_비중은_구간_비용_합을_구간_비용_전_손익_합으로_나눈다() {
        // 구간 +30%, −25%, +10%, −5%, 비용 각 50만 원: 비용 전 손익 350만 − 200만 + 150만 + 0 = 300만, 비용 200만 → 2/3
        List<WindowRun> runs = List.of(window(1, 13_000_000, 500_000, "1.5"), window(2, 7_500_000, 500_000, "2"),
                window(3, 11_000_000, 500_000, "0.5"), window(4, 9_500_000, 500_000, "1"));
        assertThat(Pooled.costShare(SEED, runs)).isEqualByComparingTo("0.666667");
        assertThat(Pooled.turnover(runs)).isEqualByComparingTo("5");
    }

    @Test
    void 비용_전_손익_합이_0_이하이거나_실패_구간이_있으면_값이_없다() {
        assertThat(Pooled.costShare(SEED, List.of(window(1, 9_000_000, 500_000, "1")))).isNull();
        WindowRun failed = new WindowRun(2, "실패", null, List.of(), List.of(), List.of(), Set.of(), EntryFunnel.Totals.ZERO,
                ExitMix.ZERO);
        assertThat(Pooled.costShare(SEED, List.of(window(1, 13_000_000, 500_000, "1"), failed))).isNull();
        assertThat(Pooled.turnover(List.of(failed))).isNull();
    }
}
