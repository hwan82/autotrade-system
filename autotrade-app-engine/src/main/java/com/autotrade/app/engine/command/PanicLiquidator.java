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
 *
 * <p>구현이 지켜야 할 계약(7단계 증권사 연동 때 테스트로 고정, ADR-006 2.5):
 * <ul>
 *   <li>단계는 위 순서로만 하고, 앞 단계가 DONE이 아니면 뒤 단계를 하지 않는다.</li>
 *   <li>서버 측 손절을 취소한 뒤 매도나 체결 확인이 실패하면, 남은 보유에 서버 측 손절을 다시 걸거나 그 실패를
 *       FAILED로 알린다. 서버 측 손절 없이 보유가 남은 상태를 DONE이나 SKIPPED로 숨기지 않는다.</li>
 *   <li>예외를 던지지 않고 결과로 알린다. 던지면 처리기는 첫 증권사 단계 FAILED로 보고 보호 모드를 유지한다.</li>
 * </ul>
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
