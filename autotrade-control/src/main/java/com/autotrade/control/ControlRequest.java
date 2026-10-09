package com.autotrade.control;

import java.util.Objects;

/**
 * 실험실(UI)이 실전 엔진에 보내는 명령 하나(ADR-006 2.3). 연결 하나에 요청 하나, JSON 한 줄.
 *
 * <p>{@code password}는 운영자 비밀번호 평문이다. 엔진이 해시와 비교한 뒤 버리고, 어디에도 저장·기록하지 않는다.
 * {@link #toString()}은 비밀번호를 가린다.
 *
 * @param command 명령 종류
 * @param account 대상 실전 계좌 별칭. PANIC_ALL과 STATUS는 비운다
 * @param typedConfirmation 사람이 직접 입력한 확인 문구(계좌 별칭 또는 {@link #PANIC_ALL_PHRASE}). 2차 확인 명령에만 쓴다
 * @param password 운영자 비밀번호. 2차 확인 명령에만 쓴다
 * @param reason 내리는 명령의 사유(감사 로그용). 비밀값을 넣지 않는다
 */
public record ControlRequest(
        ControlCommand command, String account, String typedConfirmation, String password, String reason) {

    /** 전체 PANIC SELL에서 사람이 그대로 입력해야 하는 문구. */
    public static final String PANIC_ALL_PHRASE = "전체 청산";

    public ControlRequest {
        Objects.requireNonNull(command, "command");
    }

    public static ControlRequest status() {
        return new ControlRequest(ControlCommand.STATUS, null, null, null, null);
    }

    public static ControlRequest activate(String account, String typed, String password) {
        return new ControlRequest(ControlCommand.ACTIVATE, account, typed, password, null);
    }

    public static ControlRequest deactivate(String account, String reason) {
        return new ControlRequest(ControlCommand.DEACTIVATE, account, null, null, reason);
    }

    public static ControlRequest panicAccount(String account, String typed, String password) {
        return new ControlRequest(ControlCommand.PANIC_ACCOUNT, account, typed, password, null);
    }

    public static ControlRequest panicAll(String typed, String password) {
        return new ControlRequest(ControlCommand.PANIC_ALL, null, typed, password, null);
    }

    @Override
    public String toString() {
        return "ControlRequest[command=" + command + ", account=" + account
                + ", password=" + (password == null || password.isEmpty() ? "" : "****") + "]";
    }
}
