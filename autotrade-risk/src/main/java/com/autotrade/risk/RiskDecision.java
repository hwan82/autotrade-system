package com.autotrade.risk;

import java.util.Objects;

/** 리스크 검증 결과. 거절은 예외가 아니라 값으로 돌려준다. */
public sealed interface RiskDecision permits RiskDecision.Approved, RiskDecision.Rejected {

    static RiskDecision approve() {
        return Approved.INSTANCE;
    }

    static RiskDecision reject(String ruleId, String reason) {
        return new Rejected(ruleId, reason);
    }

    default boolean isApproved() {
        return this instanceof Approved;
    }

    enum Approved implements RiskDecision {
        INSTANCE
    }

    /**
     * @param ruleId 거절한 규칙 ID(예: {@code BLACKLIST}, {@code DAILY_LOSS_LIMIT}). 거절 사유 로그와 화면에 쓴다.
     * @param reason 사람이 읽는 사유. 비밀값을 넣지 않는다
     */
    record Rejected(String ruleId, String reason) implements RiskDecision {
        public Rejected {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(reason, "reason");
            if (ruleId.isBlank()) {
                throw new IllegalArgumentException("ruleId가 비었습니다");
            }
        }
    }
}
