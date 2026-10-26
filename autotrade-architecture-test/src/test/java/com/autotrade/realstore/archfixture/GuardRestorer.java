package com.autotrade.realstore.archfixture;

import com.autotrade.domain.account.AccountId;
import com.autotrade.risk.guard.GuardState;
import com.autotrade.risk.guard.RealTradingGuard;

/** 위반 고정 예제: 저장소 코드가 원장 내용으로 Guard를 조작한다(REAL_STORE_DOES_NOT_DRIVE_GUARD, ADR-015 2.7 ①). */
public final class GuardRestorer {

    private GuardRestorer() {}

    public static GuardState peek(RealTradingGuard guard, AccountId id) {
        return guard.state(id);
    }
}
