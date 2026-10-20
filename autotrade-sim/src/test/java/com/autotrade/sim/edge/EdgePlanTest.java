package com.autotrade.sim.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EdgePlanTest {

    static final String OK = """
            id: "EXP-20261020-EDGE01"
            question: "q"
            market: "KRX"
            bars-snapshot: "krx-daily-2026-10-16-a6df4719"
            selection: "kr-intraday-hot@1"
            test: "RANGE"
            family-size: "3"
            seed: "20261020"
            null-seeds: "999"
            trade-notional: "1000000"
            holdout-from: "2026-04-01"
            parallelism: "4"
            windows:
              - "2015-07-01..2016-06-30"
              - "2016-07-01..2017-06-30"
              - "2017-07-01..2018-06-30"
            descriptive:
              - "2010-03-02..2015-06-12"
            """;

    @Test
    void 등록_파일을_읽는다() {
        EdgePlan p = EdgePlan.parse(OK, "t");
        assertThat(p.windows()).hasSize(3);
        assertThat(p.windows().get(0).from()).isEqualTo(LocalDate.of(2015, 7, 1));
        assertThat(p.descriptive()).hasSize(1);
        assertThat(p.disclosuresSnapshot()).isEmpty();
        assertThat(p.alpha()).isEqualTo(0.05 / 3);
        assertThat(p.test()).isEqualTo(EdgePlan.Test.RANGE);
    }

    @Test
    void 판정_구간은_3개_이상() {
        String two = OK.replace("  - \"2017-07-01..2018-06-30\"\n", "");
        assertThatThrownBy(() -> EdgePlan.parse(two, "t")).hasMessageContaining("3개 이상");
    }

    @Test
    void 구간은_겹치지_않고_오름차순() {
        String overlap = OK.replace("2016-07-01..2017-06-30", "2016-06-30..2017-06-30");
        assertThatThrownBy(() -> EdgePlan.parse(overlap, "t")).hasMessageContaining("겹치지 않고");
    }

    @Test
    void 표본_외_보존_구간에_들어가면_거절() {
        String late = OK.replace("2017-07-01..2018-06-30", "2017-07-01..2026-04-01");
        assertThatThrownBy(() -> EdgePlan.parse(late, "t")).hasMessageContaining("표본 외 보존");
    }

    @Test
    void 기술_통계_구간이_판정_구간과_겹치면_거절() {
        String bad = OK.replace("2010-03-02..2015-06-12", "2010-03-02..2015-07-01");
        assertThatThrownBy(() -> EdgePlan.parse(bad, "t")).hasMessageContaining("겹칩니다");
    }

    @Test
    void 대조군_시드는_N_더하기_1이_20m_이상() {
        assertThatThrownBy(() -> EdgePlan.parse(OK.replace("null-seeds: \"999\"", "null-seeds: \"58\""), "t"))
                .hasMessageContaining("N + 1 ≥ 20m");
        assertThat(EdgePlan.parse(OK.replace("null-seeds: \"999\"", "null-seeds: \"59\""), "t").nullSeeds()).isEqualTo(59);
    }

    @Test
    void 스냅샷은_ID로_고정한다() {
        assertThatThrownBy(() -> EdgePlan.parse(OK.replace("krx-daily-2026-10-16-a6df4719", "latest"), "t"))
                .hasMessageContaining("latest");
        String disc = OK.replace("selection:", "disclosures-snapshot: \"latest\"\nselection:");
        assertThatThrownBy(() -> EdgePlan.parse(disc, "t")).hasMessageContaining("latest");
        String ok = OK.replace("selection:", "disclosures-snapshot: \"dart-disclosures-2026-10-30-12345678\"\nselection:");
        assertThat(EdgePlan.parse(ok, "t").disclosuresSnapshot()).contains("dart-disclosures-2026-10-30-12345678");
    }

    @Test
    void 모르는_키와_틀린_값은_거절() {
        assertThatThrownBy(() -> EdgePlan.parse(OK + "extra: \"1\"\n", "t")).hasMessageContaining("모르는 키");
        assertThatThrownBy(() -> EdgePlan.parse(OK.replace("\"RANGE\"", "\"MEAN\""), "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EdgePlan.parse(OK.replace("EXP-20261020-EDGE01", "edge01"), "t"))
                .hasMessageContaining("실험 ID");
    }
}
