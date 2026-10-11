package com.autotrade.sim.run;

/**
 * {@link RunGate}가 멈추라고 해 실험 전체를 끝냄(D-063). Run 하나의 실패(계좌 격리, 결과에 FAILED로 남김)와 달리 결과를 남기지
 * 않고 실행 명령까지 올라간다. 실행 명령은 종료 코드 4로 끝난다(수집기와 같은 "실전 PC 자원 보호로 멈춤").
 */
public final class ExperimentStoppedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExperimentStoppedException(String reason) {
        super(reason);
    }
}
