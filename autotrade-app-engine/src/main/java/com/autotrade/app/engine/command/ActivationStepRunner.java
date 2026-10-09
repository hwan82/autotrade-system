package com.autotrade.app.engine.command;

import com.autotrade.domain.account.AccountRef;
import com.autotrade.risk.guard.ActivationStep;
import java.util.Optional;

/**
 * 활성화 단계 하나를 실제로 수행한다(RSK-01: 인증 → 주문 세션 → 첫 대사). 성공하면 빈 값, 실패하면 사유.
 * 사유에는 비밀값(토큰, 계좌번호)을 넣지 않는다. Guard 전이는 호출자({@link EngineCommandHandler})가 한다.
 */
@FunctionalInterface
public interface ActivationStepRunner {

    Optional<String> perform(AccountRef account, ActivationStep step);

    /**
     * 증권사 주문 연동 전(7단계 전)의 기본 구현. 첫 단계에서 실패해 계좌는 출발 상태로 돌아간다.
     * 명령 채널, 2차 확인, Guard 전이는 끝까지 실제로 돌지만 실전 주문 세션은 절대 만들어지지 않는다.
     */
    ActivationStepRunner BROKER_NOT_CONNECTED = (account, step) ->
            Optional.of("증권사 주문 연동 전이라 " + step + " 단계를 수행할 수 없습니다(7단계 작업)");
}
