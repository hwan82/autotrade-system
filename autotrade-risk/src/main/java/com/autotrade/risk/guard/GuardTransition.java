package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import java.time.Instant;
import java.util.Objects;

/**
 * Guard 상태 전이 감사 기록. 전이마다 하나씩 남긴다(D-023 공통 원칙 2).
 *
 * @param actor 전이를 일으킨 주체. 사람의 2차 확인이면 확인한 사람, 아니면 {@link #SYSTEM}
 * @param reason 사유. 비밀값을 넣지 않는다
 */
public record GuardTransition(
        AccountId account, GuardState from, GuardState to, String actor, String reason, Instant at) {

    public static final String SYSTEM = "system";

    public GuardTransition {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(at, "at");
    }
}
