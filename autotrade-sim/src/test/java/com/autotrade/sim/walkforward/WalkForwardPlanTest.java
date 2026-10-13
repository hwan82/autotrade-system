package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WalkForwardPlanTest {

    static final String VALID = """
            id: "EXP-20261020-WF01"
            question: "스윙·중기 일봉 후보가 지수와 대조군을 넘는가"
            market: "KRX"
            bars-snapshot: "krx-daily-2026-10-08-1a2b3c4d"
            financials-snapshot: "dart-financials-2026-10-08-5e6f7a8b"
            seed: "20261020"
            seed-capital: "10000000"
            parallelism: "4"
            first-test-from: "2022-04-01"
            train-years: "3"
            test-years: "1"
            windows: "4"
            warmup-days: "260"
            null-seeds: "400"
            combinations:
              - "none x kr-ma-trend@1"
              - "kr-fin-quality-growth@1 x kr-ma-trend-position@1"
            controls:
              - "SWING kr-random-daily-swing@1"
              - "POSITION kr-random-daily-position@1"
            blacklist:
              - "005930"
            """;

    @Test
    void 정상_등록_파일을_읽는다() {
        WalkForwardPlan p = WalkForwardPlan.parse(VALID, "study.yaml");
        assertThat(p.id().toString()).contains("EXP-20261020-WF01");
        assertThat(p.firstTestFrom()).isEqualTo(LocalDate.of(2022, 4, 1));
        assertThat(p.trainYears()).isEqualTo(3);
        assertThat(p.windows()).isEqualTo(4);
        assertThat(p.warmupDays()).isEqualTo(260);
        assertThat(p.nullSeeds()).isEqualTo(400);
        assertThat(p.m()).isEqualTo(2);
        assertThat(p.combinations().get(1).text()).isEqualTo("kr-fin-quality-growth@1 x kr-ma-trend-position@1");
        assertThat(p.controls()).containsOnlyKeys(TradingStyle.SWING, TradingStyle.POSITION);
        assertThat(p.controls().get(TradingStyle.SWING).text()).isEqualTo("kr-random-daily-swing@1");
        assertThat(p.financialsSnapshot()).contains("dart-financials-2026-10-08-5e6f7a8b");
        assertThat(p.blacklist()).containsExactly("005930");
    }

    @Test
    void 모르는_키와_중복_키와_중복_조합을_거절한다() {
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID + "extra: \"1\"\n", "f")).hasMessageContaining("모르는 키");
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID + "seed: \"1\"\n", "f")).hasMessageContaining("같은 키");
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID.replace("kr-fin-quality-growth@1 x kr-ma-trend-position@1",
                "none x kr-ma-trend@1"), "f")).hasMessageContaining("같은 조합");
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID.replace("POSITION kr-random-daily-position@1",
                "SWING kr-random-daily-swing@2"), "f")).hasMessageContaining("같은 스타일");
    }

    @Test
    void 대조군_형식과_구간_수_하한을_검사한다() {
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID.replace("SWING kr-random-daily-swing@1",
                "kr-random-daily-swing@1"), "f")).hasMessageContaining("STYLE id@버전");
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID.replace("windows: \"4\"", "windows: \"2\""), "f"))
                .hasMessageContaining("windows");
        WalkForwardPlan.parse(VALID.replace("windows: \"4\"", "windows: \"3\""), "f"); // 경계는 통과
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID.replace("null-seeds: \"400\"", "null-seeds: \"0\""), "f"))
                .hasMessageContaining("null-seeds");
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID.replace("  - \"005930\"", "  - \"abc\""), "f"))
                .hasMessageContaining("종목 코드");
    }

    @Test
    void 필수_키가_없으면_거절하고_재무_스냅샷은_생략할_수_있다() {
        assertThatThrownBy(() -> WalkForwardPlan.parse(VALID.replace("warmup-days: \"260\"\n", ""), "f"))
                .hasMessageContaining("warmup-days");
        WalkForwardPlan p = WalkForwardPlan.parse(
                VALID.replace("financials-snapshot: \"dart-financials-2026-10-08-5e6f7a8b\"\n", ""), "f");
        assertThat(p.financialsSnapshot()).isEmpty();
    }
}
