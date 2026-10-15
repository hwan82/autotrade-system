package com.autotrade.sim.run;

/**
 * 사람이 실험을 취소해 끝냄(D-089). {@link ExperimentStoppedException}과 같이 결과를 남기지 않고 실행 명령까지 올라가지만,
 * 자원 보호 정지(종료 코드 4)와 구별되게 종료 코드 3으로 끝난다.
 */
public final class ExperimentCancelledException extends ExperimentStoppedException {

    private static final long serialVersionUID = 1L;

    public ExperimentCancelledException(String reason) {
        super(reason);
    }

    @Override
    public int exitCode() {
        return 3;
    }
}
