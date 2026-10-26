package com.autotrade.app.engine.command;

import com.autotrade.control.PanicStepResult;
import com.autotrade.domain.account.AccountRef;
import java.util.List;

/** PANIC SELL 단계 이름과 결과 값(RSK-13 순서). */
public final class PanicSteps {

    public static final String PROTECTIVE_LOCK = "PROTECTIVE_LOCK";
    public static final String CANCEL_OPEN_ORDERS = "CANCEL_OPEN_ORDERS";
    public static final String CANCEL_SERVER_STOPS = "CANCEL_SERVER_STOPS";
    public static final String SELL = "SELL";
    public static final String CONFIRM_FILLS = "CONFIRM_FILLS";
    public static final String GUARD_OFF = "GUARD_OFF";

    public static final String DONE = "DONE";
    public static final String SKIPPED = "SKIPPED";
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    public static final String FAILED = "FAILED";
    /** 응답 기한 안에 끝나지 않아 아직 진행 중(청산은 계속, 결과는 상태표의 마지막 결과, D-142). DONE이 아니므로 Guard OFF도 없다. */
    public static final String PENDING = "PENDING";

    /** 증권사 단계(2~5)가 모두 이 순서로 DONE이어야 Guard를 끈다. */
    static final List<String> BROKER_STEPS = List.of(CANCEL_OPEN_ORDERS, CANCEL_SERVER_STOPS, SELL, CONFIRM_FILLS);

    private PanicSteps() {}

    public static PanicStepResult notAvailable(AccountRef account, String step) {
        return new PanicStepResult(account.id().value(), step, NOT_AVAILABLE, "증권사 주문 연동 전이라 수행할 수 없습니다(7단계 작업)");
    }

    static PanicStepResult result(AccountRef account, String step, String outcome, String note) {
        return new PanicStepResult(account.id().value(), step, outcome, note);
    }
}
