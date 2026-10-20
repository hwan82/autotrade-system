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

    /** 참고 계좌(ADR-014 F1)·묶음별 대조군 시드 수(F3) 키 읽기. 역할과 맞는지는 {@link JudgeRoles}가 본다. */
    @Test
    void readsReferencesAndGroupNullSeeds() {
        String base = yaml("input: \"live\"").replace("  - \"kr-bh-baseline@1\"\n",
                "  - \"kr-bh-baseline@1\"\n  - \"kr-orb-30@1\"\n") + "selections:\n  - \"kr-intraday-hot@1 x kr-orb-30@1\"\n"
                + "selection-input:\n  daily: \"latest\"\n";
        LivePlan p = LivePlan.parse(base + "references:\n  - \"kr-intraday-hot@1 x kr-orb-30@1\"\n  - \"kr-orb-30@1\"\n"
                + "group-null-seeds:\n  - \"kr-intraday-hot@1 96\"\n  - \"none 999\"\n", "f");
        assertThat(p.references()).containsExactly("kr-intraday-hot@1 x kr-orb-30@1", "kr-orb-30@1");
        assertThat(p.referenceAccounts()).containsExactly(1, 2); // profiles 다음 selections 순서
        assertThat(p.groupNullSeeds()).containsExactly(java.util.Map.entry("kr-intraday-hot@1", 96),
                java.util.Map.entry("none", 999));
        assertThat(p.hasJudgeKeys()).isTrue();

        // 빈 목록은 키가 없는 것과 같다(architect PR #204 m2)
        LivePlan empty = LivePlan.parse(base + "references:\ngroup-null-seeds:\n", "f");
        assertThat(empty.hasJudgeKeys()).isFalse();
        assertThat(empty).isEqualTo(LivePlan.parse(base, "f"));
    }

    @Test
    void refusesReferencesThatAreNotRegisteredLinesOrRepeated() {
        String base = yaml("input: \"live\"");
        assertThatThrownBy(() -> LivePlan.parse(base + "references:\n  - \"kr-orb-30@1\"\n", "f"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("profiles·selections 줄에 없습니다");
        // 글자 그대로가 아니면(따옴표 안 공백 등) 다른 줄이다
        assertThatThrownBy(() -> LivePlan.parse(base + "references:\n  - \"kr-bh-baseline@01\"\n", "f"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("profiles·selections 줄에 없습니다");
        assertThatThrownBy(() -> LivePlan.parse(base + "references:\n  - \"kr-bh-baseline@1\"\n  - \"kr-bh-baseline@1\"\n",
                "f")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("두 번");
    }

    @Test
    void refusesMalformedOrRepeatedGroupNullSeeds() {
        String base = yaml("input: \"live\"") + "group-null-seeds:\n";
        for (String bad : java.util.List.of("none", "none 0", "none 1000", "none 083", "none -1", "kr-intraday-hot 50",
                "kr-intraday-hot@1  50", "None 50", "none 50 x")) {
            assertThatThrownBy(() -> LivePlan.parse(base + "  - \"" + bad + "\"\n", "f")).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("group-null-seeds");
        }
        assertThatThrownBy(() -> LivePlan.parse(base + "  - \"none 50\"\n  - \"none 60\"\n", "f"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("같은 묶음이 두 번");
        assertThat(LivePlan.parse(base + "  - \"none 1\"\n", "f").groupNullSeeds()).containsEntry("none", 1);
    }
}
