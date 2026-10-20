package com.autotrade.sim.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 녹화 판정 계좌 역할·묶음별 대조군 시드 수(ADR-014 3·4장): 참고 계좌, group-null-seeds, 거절 조건. */
class JudgeRolesTest {

    private static final Path REPO = Path.of("..");

    /** 계좌: 0 기준선, 1 대조군(선정 없음), 2 orb, 3 vwap, 4 hot x orb, 5 hot x 대조군, 6 momentum x orb(대조군 없음). */
    private static LivePlan plan(String... extra) {
        StringBuilder sb = new StringBuilder(String.join("\n",
                "id: \"EXP-TEST-ROLES\"",
                "question: \"q\"",
                "input: \"live\"",
                "market: \"KRX\"",
                "from: \"2026-10-14\"",
                "to: \"2026-10-15\"",
                "seed: \"1\"",
                "seed-capital: \"10000000\"",
                "profiles:",
                "  - \"kr-bh-baseline@1\"",
                "  - \"kr-random-control@1\"",
                "  - \"kr-orb-30@1\"",
                "  - \"kr-vwap-reclaim@1\"",
                "boards:",
                "  - \"005930 KOSPI\"",
                "selections:",
                "  - \"kr-intraday-hot@1 x kr-orb-30@1\"",
                "  - \"kr-intraday-hot@1 x kr-random-control@1\"",
                "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\"",
                "selection-input:",
                "  daily: \"latest\"",
                ""));
        for (String e : extra) {
            sb.append(e).append('\n');
        }
        return LivePlan.parse(sb.toString(), "f");
    }

    private static JudgeRoles.Roles roles(LivePlan plan) {
        return JudgeRoles.of(LiveSetup.of(plan, REPO));
    }

    /** momentum 묶음에는 대조군이 없으므로, 그 계좌를 참고로 빼야 판정할 수 있다(계획 5장 R3 모양). */
    private static final String[] MOMENTUM_REFERENCE = {"references:", "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\""};

    @Test
    void referencesLeaveTheFamilyAndNeedNoControlOfTheirOwn() {
        assertThatThrownBy(() -> roles(plan())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kr-intraday-open-momentum@1의 판정 대상");

        JudgeRoles.Roles r = roles(plan(MOMENTUM_REFERENCE));
        assertThat(r.baseline()).isZero();
        assertThat(r.controls()).containsExactly(1, 5);
        assertThat(r.judged()).containsExactly(2, 3, 4);
        assertThat(r.m()).isEqualTo(3);
        assertThat(r.references()).containsExactly(6);
        assertThat(r.nullControls()).containsExactly(1, 5);
        assertThat(r.seedsByControl()).isEmpty();
        assertThat(r.seedsFor(1, 62)).isEqualTo(62);

        // 대조군 있는 묶음의 계좌를 참고로: m에서 빠지고, 그 묶음에 판정 대상이 없으면 그 대조군 시드도 돌리지 않는다
        JudgeRoles.Roles more = roles(plan("references:", "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\"",
                "  - \"kr-intraday-hot@1 x kr-orb-30@1\"", "  - \"kr-vwap-reclaim@1\""));
        assertThat(more.judged()).containsExactly(2);
        assertThat(more.references()).containsExactly(3, 4, 6);
        assertThat(more.nullControls()).containsExactly(1);
    }

    @Test
    void refusesBaselineOrControlAsReferenceAndNoJudgedLeft() {
        assertThatThrownBy(() -> roles(plan("references:", "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\"",
                "  - \"kr-bh-baseline@1\""))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references에 기준선(logic: buy-and-hold) 계좌를 적었습니다: kr-bh-baseline@1");
        assertThatThrownBy(() -> roles(plan("references:", "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\"",
                "  - \"kr-intraday-hot@1 x kr-random-control@1\""))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references에 대조군(logic: random) 계좌를 적었습니다: "
                        + "kr-intraday-hot@1 x kr-random-control@1");
        assertThatThrownBy(() -> roles(plan("references:", "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\"",
                "  - \"kr-intraday-hot@1 x kr-orb-30@1\"", "  - \"kr-vwap-reclaim@1\"", "  - \"kr-orb-30@1\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("판정 대상 하나 이상")
                .hasMessageContaining("참고 계좌는 판정 대상에 넣지 않음");
    }

    @Test
    void groupNullSeedsRaiseOnlyTheNamedGroupAndNeverBelowTwentyOneMMinusOne() {
        // m = 3 → 21m − 1 = 62
        JudgeRoles.Roles r = roles(plan(concat(MOMENTUM_REFERENCE, "group-null-seeds:", "  - \"kr-intraday-hot@1 130\"")));
        assertThat(r.seedsByControl()).containsExactly(java.util.Map.entry(5, 130));
        assertThat(r.seedsFor(5, 62)).isEqualTo(130);
        assertThat(r.seedsFor(1, 62)).isEqualTo(62);
        assertThatCode(() -> roles(plan(concat(MOMENTUM_REFERENCE, "group-null-seeds:", "  - \"none 62\""))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> roles(plan(concat(MOMENTUM_REFERENCE, "group-null-seeds:", "  - \"none 61\""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("선정 없음(none) 시드 수 61가 21m − 1 = 62(m = 3)보다 작습니다");
        // 대조군이 없는 묶음, 판정 대상이 없는 묶음의 시드 수는 쓰이지 않으므로 실수로 본다
        assertThatThrownBy(() -> roles(plan(concat(MOMENTUM_REFERENCE, "group-null-seeds:",
                "  - \"kr-intraday-open-momentum@1 80\"")))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("선정 kr-intraday-open-momentum@1 묶음에 대조군(logic: random) 계좌가 없습니다");
        assertThatThrownBy(() -> roles(plan("references:", "  - \"kr-intraday-open-momentum@1 x kr-orb-30@1\"",
                "  - \"kr-intraday-hot@1 x kr-orb-30@1\"", "group-null-seeds:", "  - \"kr-intraday-hot@1 80\"")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("판정 대상이 없어");
        assertThatThrownBy(() -> roles(plan(concat(MOMENTUM_REFERENCE, "group-null-seeds:", "  - \"kr-other@1 80\""))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("선정 kr-other@1 묶음에 대조군");
    }

    @Test
    void sessionStartCheckLooksOnlyAtRegistrationsWithTheNewKeys() {
        // 판정 역할로는 틀린 등록(momentum 묶음 대조군 없음)이지만 새 키가 없으면 세션 시작 때 보지 않는다(LIVE00·LIVE01 모양)
        assertThatCode(() -> JudgeRoles.check(plan(), REPO)).doesNotThrowAnyException();
        assertThatCode(() -> JudgeRoles.check(plan("references:", "group-null-seeds:"), REPO)).doesNotThrowAnyException();
        assertThatThrownBy(() -> JudgeRoles.check(plan("references:", "  - \"kr-vwap-reclaim@1\""), REPO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kr-intraday-open-momentum@1의 판정 대상");
        assertThatCode(() -> JudgeRoles.check(plan(MOMENTUM_REFERENCE), REPO)).doesNotThrowAnyException();
    }

    @Test
    void precheckRolesMayHaveNoBaselineAndSkipTheLowerBound() {
        LiveSetup setup = LiveSetup.of(plan(MOMENTUM_REFERENCE), REPO);
        List<com.autotrade.selection.profile.Combination> noBaseline = setup.combinations().subList(1,
                setup.combinations().size());
        JudgeRoles.Roles r = JudgeRoles.of(noBaseline, List.of(5), java.util.Map.of("none", 5), false);
        assertThat(r.baseline()).isEqualTo(-1);
        assertThat(r.judged()).containsExactly(1, 2, 3);
        assertThat(r.seedsFor(0, 62)).isEqualTo(5);
        assertThatThrownBy(() -> JudgeRoles.of(noBaseline, List.of(5), java.util.Map.of(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("기준선");
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
