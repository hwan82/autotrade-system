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

    /**
     * 올라가는 전이인지(OFF &lt; PROTECTIVE &lt; ACTIVATING &lt; OPEN 순서로 위로 가는가). 감사 어댑터는 이것으로 올림(기록을 기다림)과
     * 내림·보정 기록(기다리지 않음)을 가른다(ADR-015 2.5). 상태를 바꾸지 않는 기록(from = to)은 올림이 아니다.
     */
    public boolean isRaise() {
        return rank(to) > rank(from);
    }

    private static int rank(GuardState s) {
        return switch (s) {
            case OFF -> 0;
            case PROTECTIVE -> 1;
            case ACTIVATING -> 2;
            case OPEN -> 3;
        };
    }
}
