package com.autotrade.app.engine.command;

import com.autotrade.domain.account.AccountId;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * ArchUnit 위반 픽스처(ADR-006, D-142 C12): 명령 채널 패키지 안에서 검증기를 거치지 않고 Guard의 발급기를 가져와 2차 확인을
 * 만드는 코드. {@code ONLY_AUTHENTICATOR_CREATES_CONFIRMATION}이 발급기 가져오기·발급 호출과 그 메서드 참조를 모두 잡아야 한다.
 * 운영 코드에서 쓰지 않는다.
 */
public final class ForgedConfirmation {

    private ForgedConfirmation() {}

    static SecondConfirmation forge(RealTradingGuard guard, AccountId account) {
        return guard.confirmationIssuer().issue(account, "operator", Instant.EPOCH);
    }

    interface Factory {
        SecondConfirmation make(AccountId account, String operator, Instant at);
    }

    static Supplier<Factory> viaReference(RealTradingGuard guard) {
        Supplier<SecondConfirmation.Issuer> take = guard::confirmationIssuer;
        return () -> take.get()::issue;
    }
}
