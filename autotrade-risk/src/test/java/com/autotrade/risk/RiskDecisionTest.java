package com.autotrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RiskDecisionTest {

    @Test
    void 승인과_거절을_구분한다() {
        assertThat(RiskDecision.approve().isApproved()).isTrue();
        RiskDecision rejected = RiskDecision.reject("BLACKLIST", "제외 종목");
        assertThat(rejected.isApproved()).isFalse();
        assertThat(rejected).isEqualTo(new RiskDecision.Rejected("BLACKLIST", "제외 종목"));
    }

    @Test
    void 규칙_ID가_없는_거절은_만들_수_없다() {
        assertThatThrownBy(() -> RiskDecision.reject(" ", "사유")).isInstanceOf(IllegalArgumentException.class);
    }
}
