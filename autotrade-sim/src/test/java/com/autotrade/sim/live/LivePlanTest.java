package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.sim.cost.KrxBoard;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 실시간 모의 등록 파일(input: live, ADR-010 2.4). */
class LivePlanTest {

    private static String yaml(String... extra) {
        StringBuilder sb = new StringBuilder(String.join("\n",
                "id: \"EXP-20261015-LIVE01\"",
                "question: \"q\"",
                "market: \"KRX\"",
                "from: \"2026-10-15\"",
                "to: \"2026-12-11\"",
                "seed: \"1\"",
                "seed-capital: \"10000000\"",
                "profiles:",
                "  - \"kr-bh-baseline@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "  - \"035720 KOSPI\"",
                ""));
        for (String e : extra) {
            sb.append(e).append('\n');
        }
        return sb.toString();
    }

    @Test
    void parsesARegistration() {
        LivePlan p = LivePlan.parse(yaml("input: \"live\"", "benchmark: \"005930\"", "blacklist:", "  - \"035720\""), "f");
        assertThat(p.id().value()).isEqualTo("EXP-20261015-LIVE01");
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(p.boards()).containsEntry("005930", KrxBoard.KOSPI).hasSize(2);
        assertThat(p.benchmark()).contains("005930");
        assertThat(p.blacklist()).containsExactly("035720");
        assertThat(LivePlan.parse(yaml("input: \"live\""), "f").benchmark()).isEqualTo(Optional.empty());
    }

    @Test
    void refusesWrongInputMissingBoardsAndBenchmarkOutsideBoards() {
        assertThatThrownBy(() -> LivePlan.parse(yaml("input: \"recording\""), "f"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("input");
        assertThatThrownBy(() -> LivePlan.parse(yaml("input: \"live\"", "benchmark: \"000660\""), "f"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("벤치마크");
        assertThatThrownBy(() -> LivePlan.parse(yaml("input: \"live\"").replace("to: \"2026-12-11\"", "to: \"2026-10-01\""),
                "f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LivePlan.parse(yaml("input: \"live\"").replace("  - \"035720 KOSPI\"\n", "")
                .replace("  - \"005930 KOSPI\"\n", ""), "f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LivePlan.parse(yaml("input: \"live\"").replace("KRX", "US"), "f"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
