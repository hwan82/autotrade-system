package com.autotrade.control;

/** 명령 채널 명령 종류(ADR-006 2.3). */
public enum ControlCommand {
    /** 계좌별 Guard 상태 조회. 확인 없음. */
    STATUS,
    /** 실전 활성화(OFF·PROTECTIVE → ACTIVATING → OPEN). 2차 확인 필요(RSK-01). */
    ACTIVATE,
    /** 내리기(OPEN → PROTECTIVE, ACTIVATING → 출발 상태). 내려가는 전이라 확인 없음(D-023 공통 원칙 2). */
    DEACTIVATE,
    /** 계좌 하나 PANIC SELL. 2차 확인 필요(RSK-13). */
    PANIC_ACCOUNT,
    /** 모든 실전 계좌 PANIC SELL. 2차 확인 필요(RSK-13). */
    PANIC_ALL;

    /** 운영자 비밀번호와 확인 문구가 있어야 하는 명령인지. */
    public boolean needsSecondConfirmation() {
        return this == ACTIVATE || this == PANIC_ACCOUNT || this == PANIC_ALL;
    }
}
