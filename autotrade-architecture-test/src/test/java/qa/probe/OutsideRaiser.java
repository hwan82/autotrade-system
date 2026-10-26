package qa.probe;

import com.autotrade.domain.account.AccountId;
import com.autotrade.risk.guard.ActivationStep;
import com.autotrade.risk.guard.RealTradingGuard;
import java.time.Instant;

/**
 * 위반 픽스처(독립 QA PR #21 E2 형태): {@code com.autotrade} 밖 패키지에서 사람 확인 없이 Guard를 올린다. 예전 검사 범위
 * ({@code importPackages("com.autotrade")})에는 보이지 않았다. {@code ModuleOwnershipTest}가 소유 패키지 밖 클래스로 잡고,
 * 넓힌 {@code ArchitectureTest} 범위에서는 명령 채널 규칙이 잡는다.
 */
public final class OutsideRaiser {

    private OutsideRaiser() {}

    public static void raise(RealTradingGuard guard) {
        AccountId account = new AccountId("KIS-1");
        guard.beginActivation(guard.confirmationIssuer().issue(account, "nobody", Instant.EPOCH));
        guard.completeStep(account, ActivationStep.AUTHENTICATED);
    }
}
