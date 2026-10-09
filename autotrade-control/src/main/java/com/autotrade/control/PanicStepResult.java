package com.autotrade.control;

/**
 * PANIC SELL 한 계좌의 한 단계 결과(RSK-13 순서).
 *
 * @param account 계좌 별칭
 * @param step 단계 이름(PROTECTIVE_LOCK, CANCEL_OPEN_ORDERS, CANCEL_SERVER_STOPS, SELL, CONFIRM_FILLS, GUARD_OFF)
 * @param outcome DONE, SKIPPED, NOT_AVAILABLE, FAILED
 * @param note 사람이 읽을 설명
 */
public record PanicStepResult(String account, String step, String outcome, String note) {
}
