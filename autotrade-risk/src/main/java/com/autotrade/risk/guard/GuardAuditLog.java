package com.autotrade.risk.guard;

/**
 * Guard 전이 감사 로그 저장소. 영속 구현(DB·파일)은 실전 엔진 저장소 작업에서 붙인다.
 *
 * <p>올라가는 전이는 기록이 성공해야 일어난다(기록 실패 시 예외가 전파되고 상태는 그대로).
 * 내려가는 전이는 기록 실패와 상관없이 먼저 일어난다. 안전 쪽 전이를 저장소 장애가 막으면 안 되기 때문이다.
 */
@FunctionalInterface
public interface GuardAuditLog {

    void record(GuardTransition transition);
}
