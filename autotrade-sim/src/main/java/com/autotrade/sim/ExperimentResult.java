package com.autotrade.sim;

import java.util.List;
import java.util.Objects;

/**
 * 실험 결과 요약. 재현에 필요한 코드 버전과 비용 표 해시를 함께 남긴다(SIM-10, D-021).
 *
 * @param codeCommit 실행한 커밋
 * @param codeDirty 커밋되지 않은 변경이 있었는지. true면 재현 보장이 없다
 * @param costTableHash 실험 기간에 실제로 쓰인 비용 표 구간의 해시. 같은 해시끼리만 성과를 비교한다
 * @param runs {@link ExperimentSpec#profiles()} 순서와 같은 순서
 */
public record ExperimentResult(
        ExperimentId experimentId, String codeCommit, boolean codeDirty, String costTableHash, List<RunResult> runs) {

    public ExperimentResult {
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(codeCommit, "codeCommit");
        Objects.requireNonNull(costTableHash, "costTableHash");
        runs = List.copyOf(runs);
    }
}
