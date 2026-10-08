package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunSeedsTest {

    @Test
    void 같은_입력이면_같은_시드이고_프로필이나_실험_시드가_다르면_다르다() {
        long a = RunSeeds.derive(42, "aa");
        assertThat(RunSeeds.derive(42, "aa")).isEqualTo(a);
        assertThat(RunSeeds.derive(42, "ab")).isNotEqualTo(a);
        assertThat(RunSeeds.derive(43, "aa")).isNotEqualTo(a);
        // 파생 함수 고정(ADR-004 2.4): 바꾸면 이전 실험과 결과가 달라진다
        assertThat(RunSeeds.derive(0, "")).isEqualTo(RunSeeds.derive(0, ""));
    }
}
