package com.autotrade.risk.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GuardTransitionTest {

    private static GuardTransition t(GuardState from, GuardState to) {
        return new GuardTransition(GuardFixtures.KIS_KR, from, to, GuardTransition.SYSTEM, "시험", Instant.EPOCH);
    }

    @Test
    void 올림과_내림을_상태_순서로_가른다() {
        assertThat(t(GuardState.OFF, GuardState.PROTECTIVE).isRaise()).isTrue();
        assertThat(t(GuardState.OFF, GuardState.ACTIVATING).isRaise()).isTrue();
        assertThat(t(GuardState.PROTECTIVE, GuardState.ACTIVATING).isRaise()).isTrue();
        assertThat(t(GuardState.ACTIVATING, GuardState.OPEN).isRaise()).isTrue();
        assertThat(t(GuardState.ACTIVATING, GuardState.PROTECTIVE).isRaise()).isFalse();
        assertThat(t(GuardState.ACTIVATING, GuardState.OFF).isRaise()).isFalse();
        assertThat(t(GuardState.OPEN, GuardState.PROTECTIVE).isRaise()).isFalse();
        assertThat(t(GuardState.PROTECTIVE, GuardState.OFF).isRaise()).isFalse();
        // 보정 기록(from = to)은 올림이 아니다
        assertThat(t(GuardState.OFF, GuardState.OFF).isRaise()).isFalse();
    }
}
