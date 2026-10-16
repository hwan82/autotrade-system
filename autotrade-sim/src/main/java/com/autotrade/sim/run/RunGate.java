package com.autotrade.sim.run;

import java.util.Optional;

/**
 * 실험을 계속 돌려도 되는지 묻는 문(D-063, broker-and-fault-design 5.2 0번). 실험 실행기는 Run을 시작하기 전과 Run 안에서
 * 거래일이 바뀔 때마다 묻고, 멈추라는 답이면 {@link ExperimentStoppedException}으로 실험 전체를 끝낸다. 결과 파일은 쓰지 않는다.
 *
 * <p>실행 명령과 실행기 사이의 제어 통로이기도 하다(SIM-14, D-089): 정지 확인 말고도 실행기는 진행 상태를
 * {@link #onProgress}로 넘기고, 동시에 돌릴 Run 수를 {@link #maxParallelRuns}로 묻는다. 두 가지 모두 결과에 영향을 주지
 * 않는다(병렬 수는 결과를 바꾸지 않고, 진행 보고는 돌려받는 값이 없다). 사람의 취소는 {@link #check}에서
 * {@link ExperimentCancelledException}으로 끝낸다.
 *
 * <p>순수 모듈은 벽시계를 읽지 못하므로(ArchUnit {@code NO_SYSTEM_TIME}) 장 시간 판단과 정지 요청 파일 확인은 실행 명령
 * 진입점({@code com.autotrade.app.experiment})이 구현해 넣는다. 결과에는 영향을 주지 않는다: 멈추지 않으면 문이 없을 때와
 * 같은 결과가 나오고, 멈추면 결과가 없다.
 */
@FunctionalInterface
public interface RunGate {

    /** 언제나 계속(테스트·실험실 화면 등 실행 명령 밖 호출). */
    RunGate NONE = () -> Optional.empty();

    /**
     * 멈춰야 하면 그 이유. 여러 Run 스레드에서 동시에 부르므로 스레드 안전해야 한다. 판단할 수 없으면(파일 확인 실패 등)
     * 멈추는 쪽으로 답한다.
     */
    Optional<String> stopReason();

    /**
     * 실행기가 넘기는 진행 상태(D-089). 여러 Run 스레드에서 부르지만 실행기가 한 실험 안에서는 차례로 넘긴다(값이 뒤로 가지
     * 않음). 빨리 돌아와야 한다(파일 쓰기는 모아서). 예외를 던지면 실행기는 그 실험의 남은 보고를 끄고 실험은 계속한다.
     * 기본은 아무것도 하지 않는다.
     */
    default void onProgress(ExperimentProgress progress) {
    }

    /**
     * 실행 명령이 이번에 실행기를 몇 번 부를지 미리 알림(명령 전체 진행률, D-097). 워크포워드처럼 한 명령이 실험을 여러 번 부르고
     * 그 수를 시작 전에 셀 수 있을 때만 부른다. 결과에는 영향이 없다(돌려받는 값이 없다). 부르는 쪽은 예외를 삼키고 계속한다.
     * 기본은 아무것도 하지 않는다.
     *
     * @param count 부를 실험 수(1 이상)
     */
    default void onExperimentsPlanned(int count) {
    }

    /**
     * 동시에 돌릴 Run 수 상한(SIM-14 실험실 자원 상한, 실험 실행 명령 옵션 {@code -PmaxParallelRuns}, D-089). 실행기는 실험 등록 파일의
     * {@code parallelism}과 이 값 중 작은 쪽을 쓴다. 결과에는 영향이 없다. 1~64. 기본 64(상한 없음과 같음).
     */
    default int maxParallelRuns() {
        return 64;
    }

    /** 멈춰야 하면 {@link ExperimentStoppedException}. */
    default void check() {
        Optional<String> reason = stopReason();
        if (reason.isPresent()) {
            throw new ExperimentStoppedException(reason.get());
        }
    }
}
