package com.autotrade.app.engine.command;

import com.autotrade.domain.account.AccountId;
import com.autotrade.risk.guard.RealTradingGuard;
import java.time.Instant;

/**
 * {@code ModuleOwnershipTest} 검증용 위반 코드(운영 코드 아님): autotrade-app-engine이 아닌 모듈이 명령 채널 패키지
 * 이름({@code com.autotrade.app.engine.command})을 빌려 사람 확인 없이 Guard를 올린다(QA 보고서 2026-10-09 F-1).
 */
public final class SplitPackageCommand {

    private SplitPackageCommand() {}

    public static void raise(RealTradingGuard guard) {
        guard.beginActivation(guard.confirmationIssuer().issue(new AccountId("KIS-1"), "nobody", Instant.EPOCH));
    }
}
