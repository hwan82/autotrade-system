package com.autotrade.control;

/** 응답 코드. 실험실 REST 오류 코드로 그대로 쓴다. */
public final class ControlCodes {
    public static final String OK = "OK";
    /** 요청 형식 오류(모르는 명령, 빠진 값, 크기 초과). */
    public static final String BAD_REQUEST = "BAD_REQUEST";
    /** 설정에 없는 실전 계좌. */
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    /** 운영자 비밀번호가 설정되지 않음(secrets.json). 2차 확인 명령은 모두 거절된다. */
    public static final String PASSWORD_NOT_CONFIGURED = "PASSWORD_NOT_CONFIGURED";
    /** 비밀번호 또는 확인 문구 불일치. 어느 쪽이 틀렸는지 알려 주지 않는다. */
    public static final String CONFIRMATION_FAILED = "CONFIRMATION_FAILED";
    /** 연속 실패로 잠김. */
    public static final String LOCKED = "LOCKED";
    /** 지금 상태에서 할 수 없는 전이(예: 이미 OPEN인 계좌 활성화, 계좌번호 없는 계좌). */
    public static final String INVALID_STATE = "INVALID_STATE";
    /** 명령 대기열이 차서 받지 않음. 반영되지 않았으니 상태를 보고 다시 보낸다. */
    public static final String BUSY = "BUSY";
    /** 올리는 명령(ACTIVATE)이 대기열에서 너무 오래 기다려 버림. 실험실은 이미 응답 없음으로 처리했을 수 있다. */
    public static final String STALE = "STALE";
    /** 엔진에 연결할 수 없음(실험실 쪽에서만 만든다). */
    public static final String ENGINE_UNREACHABLE = "ENGINE_UNREACHABLE";
    /** 엔진 내부 오류. */
    public static final String INTERNAL = "INTERNAL";

    private ControlCodes() {}
}
