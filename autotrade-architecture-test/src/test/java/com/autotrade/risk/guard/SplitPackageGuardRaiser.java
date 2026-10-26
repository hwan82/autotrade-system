package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import java.time.Instant;

/**
 * {@code ModuleOwnershipTest} 검증용 위반 코드(운영 코드 아님): autotrade-risk가 아닌 모듈이 {@code com.autotrade.risk.guard}
 * 패키지 이름을 빌려 Guard를 올린다. 패키지 이름만 보는 {@code ONLY_COMMAND_CHANNEL_RAISES_GUARD}는 이 코드를 허용 범위로
 * 본다(QA 보고서 2026-10-09 F-1 실측). 모듈 소유 규칙이 이 클래스를 잡아야 한다.
 */
public final class SplitPackageGuardRaiser {

    private SplitPackageGuardRaiser() {}

    public static void raise(RealTradingGuard guard) {
        AccountId account = new AccountId("KIS-1");
        guard.beginActivation(guard.confirmationIssuer().issue(account, "nobody", Instant.EPOCH));
        guard.completeStep(account, ActivationStep.AUTHENTICATED);
    }
}
