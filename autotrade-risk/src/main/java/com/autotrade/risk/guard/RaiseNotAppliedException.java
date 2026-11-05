package com.autotrade.risk.guard;

/**
 * 올라가는 전이를 적용하지 않았다(ADR-015 2.5 "올림 감사와 계좌 잠금"). 상태는 출발 상태 그대로이거나, 그 사이 내림이 먼저 일어난
 * 상태다. 2차 확인은 이미 썼으므로 사람이 다시 확인해야 한다. 명령 응답과 화면은 {@link #reason()}으로 두 사유를 구별해 보인다.
 */
public final class RaiseNotAppliedException extends GuardClosedException {

    private static final long serialVersionUID = 1L;

    /** 적용하지 않은 이유. */
    public enum Reason {
        /** 감사 기록이 실패했거나 상한 안에 끝나지 않았다(저장소 차단 포함). 올라가는 전이는 기록이 성공해야 한다. */
        AUDIT_NOT_RECORDED,
        /** 감사를 기록하는 사이 같은 계좌의 내림(킬스위치·PANIC·활성화 실패·내리기)이 먼저 일어났다. */
        LOWERED_FIRST
    }

    private final Reason reason;

    RaiseNotAppliedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
