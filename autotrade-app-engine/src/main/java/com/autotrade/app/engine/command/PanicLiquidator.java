package com.autotrade.app.engine.command;

import com.autotrade.control.PanicStepResult;
import com.autotrade.domain.account.AccountRef;
import java.util.List;

/**
 * PANIC SELL의 증권사 단계(RSK-13 2~5단계: 미체결 전부 취소 확인 → 서버 측 손절 취소 확인 → 매도 → 체결 확인).
 * 1단계(보호 모드 고정)와 6단계(Guard OFF)는 {@link EngineCommandHandler}가 한다.
 *
 * <p>구현은 대상에서 블랙리스트·수동 보유 종목을 빼고(RSK-12), 장 외 시간에는 취소만 하고 매도하지 않는다(RSK-13).
 * 단계 이름은 {@link PanicSteps}의 상수를 쓴다.
 */
@FunctionalInterface
public interface PanicLiquidator {

    List<PanicStepResult> liquidate(AccountRef account);

    /** 증권사 주문 연동 전(7단계 전)의 기본 구현. 아무 주문도 내지 않고 단계마다 수행 불가를 알린다. */
    PanicLiquidator BROKER_NOT_CONNECTED = account -> List.of(
            PanicSteps.notAvailable(account, PanicSteps.CANCEL_OPEN_ORDERS),
            PanicSteps.notAvailable(account, PanicSteps.CANCEL_SERVER_STOPS),
            PanicSteps.notAvailable(account, PanicSteps.SELL),
            PanicSteps.notAvailable(account, PanicSteps.CONFIRM_FILLS));
}
