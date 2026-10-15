package com.autotrade.sim.run;

import java.util.Objects;

/**
 * 실험 하나(실행기 호출 한 번)의 진행 상태(SIM-14 진행 보고, D-089). 실행기가 {@link RunGate#onProgress}로 넘긴다.
 *
 * <p>진행 보고는 결과에 영향을 주지 않는다. 실행기는 이 값을 만들어 넘기기만 하고 돌려받는 것이 없으며, 받는 쪽이 예외를
 * 던지면 그 실험의 남은 보고를 끈다(실험은 계속).
 *
 * @param experimentId 실험 ID(워크포워드처럼 한 명령이 여러 실험을 돌리면 지금 실험의 ID)
 * @param phase 시작(Run 시작 전), 진행, 끝(모든 Run이 끝남)
 * @param runsTotal 조합 수 = Run 수
 * @param runsStarted 시작한 Run 수
 * @param runsFinished 끝난 Run 수(실패 포함)
 * @param runsFailed 실패로 끝난 Run 수(계좌 격리, 결과에 FAILED로 남는 Run)
 * @param daysPerRun Run 하나가 도는 거래일 수(예열 제외)
 * @param daysDone 모든 Run이 마감한 거래일 수의 합. 끝난 Run은 남은 날까지 다 센다(실패한 Run 포함)
 */
public record ExperimentProgress(
        String experimentId,
        Phase phase,
        int runsTotal,
        int runsStarted,
        int runsFinished,
        int runsFailed,
        int daysPerRun,
        long daysDone) {

    /** 실험 안에서의 단계. */
    public enum Phase {
        STARTED,
        RUNNING,
        FINISHED
    }

    public ExperimentProgress {
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(phase, "phase");
        if (runsTotal < 0 || runsStarted < 0 || runsStarted > runsTotal || runsFinished < 0
                || runsFinished > runsStarted || runsFailed < 0 || runsFailed > runsFinished || daysPerRun < 0
                || daysDone < 0 || daysDone > (long) runsTotal * daysPerRun) {
            throw new IllegalArgumentException("진행 값이 맞지 않습니다: " + runsTotal + "/" + runsStarted + "/"
                    + runsFinished + "/" + runsFailed + "/" + daysPerRun + "/" + daysDone);
        }
    }

    /** 전체 거래일 수(Run 수 × Run당 거래일). */
    public long daysTotal() {
        return (long) runsTotal * daysPerRun;
    }

    /** 진행률 천분율(0~1000). 거래일이 없으면 끝난 Run 비율. 표시용이라 정수로 내림한다. */
    public int permille() {
        long total = daysTotal();
        if (total > 0) {
            return (int) (daysDone * 1000 / total);
        }
        return runsTotal == 0 ? (phase == Phase.FINISHED ? 1000 : 0) : runsFinished * 1000 / runsTotal;
    }
}
