package com.autotrade.sim.run;

import java.util.Optional;

/**
 * 실험을 계속 돌려도 되는지 묻는 문(D-063, broker-and-fault-design 5.2 0번). 실험 실행기는 Run을 시작하기 전과 Run 안에서
 * 거래일이 바뀔 때마다 묻고, 멈추라는 답이면 {@link ExperimentStoppedException}으로 실험 전체를 끝낸다. 결과 파일은 쓰지 않는다.
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

    /** 멈춰야 하면 {@link ExperimentStoppedException}. */
    default void check() {
        Optional<String> reason = stopReason();
        if (reason.isPresent()) {
            throw new ExperimentStoppedException(reason.get());
        }
    }
}
