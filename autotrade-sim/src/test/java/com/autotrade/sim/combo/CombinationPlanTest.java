package com.autotrade.sim.combo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CombinationPlanTest {

    static final String SAMPLE = """
            # 조합 실험 등록
            id: "EXP-20261012-CMB01"
            question: "(나) 선정이 \\"선정 없음\\"보다 나은가"
            market: "KRX"
            from: "2025-01-02"
            to: 2026-10-08   # 따옴표 없는 값 뒤 주석
            seed: "20261012"
            seed-capital: "10000000"
            bars-snapshot: "krx-daily-2026-10-08-1a2b3c4d"
            financials-snapshot: "dart-financials-2026-10-08-5e6f7a8b"
            benchmark: "none"
            parallelism: "4"
            combinations:
              - "kr-vol-surge-quiet@1 x kr-ma-trend@1"
              - "none x kr-ma-trend@1"
            blacklist:   # 실험 시작 시점 제외 종목
              - "005930"
              - "000660"
            """;

    @Test
    void 등록_파일을_읽는다() {
        CombinationPlan p = CombinationPlan.parse(SAMPLE, "experiment.yaml");

        assertThat(p.id().value()).isEqualTo("EXP-20261012-CMB01");
        assertThat(p.question()).isEqualTo("(나) 선정이 \"선정 없음\"보다 나은가");
        assertThat(p.from()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(p.to()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(p.seedCapital()).isEqualTo(Money.of(10_000_000, Money.KRW));
        assertThat(p.financialsSnapshot()).contains("dart-financials-2026-10-08-5e6f7a8b");
        assertThat(p.disclosuresSnapshot()).isEmpty();
        assertThat(p.benchmark()).isEmpty();
        assertThat(p.parallelism()).isEqualTo(4);
        assertThat(p.combinations()).extracting(CombinationPlan.Entry::text)
                .containsExactly("kr-vol-surge-quiet@1 x kr-ma-trend@1", "none x kr-ma-trend@1");
        assertThat(p.combinations().get(1).selection()).isEqualTo(Optional.empty());
        assertThat(p.blacklist()).containsExactly("000660", "005930");
    }

    @Test
    void 모르는_키_같은_키_같은_조합_형식_오류를_거절한다() {
        assertThatThrownBy(() -> CombinationPlan.parse(SAMPLE + "slippage: \"0\"\n", "f"))
                .hasMessageContaining("모르는 키").hasMessageContaining("f:");
        assertThatThrownBy(() -> CombinationPlan.parse(SAMPLE.replace("parallelism: \"4\"", "seed: \"1\""), "f"))
                .hasMessageContaining("같은 키");
        assertThatThrownBy(() -> CombinationPlan.parse(SAMPLE.replace("none x kr-ma-trend@1",
                "kr-vol-surge-quiet@1 x kr-ma-trend@1"), "f")).hasMessageContaining("같은 조합");
        assertThatThrownBy(() -> CombinationPlan.parse(SAMPLE.replace("none x kr-ma-trend@1", "kr-ma-trend@1"), "f"))
                .hasMessageContaining("형식");
        assertThatThrownBy(() -> CombinationPlan.parse(SAMPLE.replace("bars-snapshot", "# bars-snapshot"), "f"))
                .hasMessageContaining("필수 키가 없습니다: bars-snapshot");
        assertThatThrownBy(() -> CombinationPlan.parse(SAMPLE.replace("  - \"005930\"", "- \"005930\""), "f"))
                .hasMessageContaining("읽을 수 없는 줄");
        assertThatThrownBy(() -> CombinationPlan.parse(SAMPLE.replace("\"000660\"", "\"66\""), "f"))
                .hasMessageContaining("종목 코드");
    }

    @Test
    void 공시_스냅샷_키를_읽는다() {
        CombinationPlan p = CombinationPlan.parse(SAMPLE.replace("benchmark:",
                "disclosures-snapshot: \"dart-disclosures-2026-10-19-9a0b1c2d\"\nbenchmark:"), "f");

        assertThat(p.disclosuresSnapshot()).contains("dart-disclosures-2026-10-19-9a0b1c2d");
    }
}
