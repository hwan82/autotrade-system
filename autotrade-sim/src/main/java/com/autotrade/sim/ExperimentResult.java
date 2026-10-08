package com.autotrade.sim;

import java.util.List;
import java.util.Objects;

/**
 * 실험 결과 요약. 재현을 위해 코드 버전(커밋)을 함께 남긴다(role-guides.md 4장 완료 기준).
 *
 * @param runs {@link ExperimentSpec#profiles()} 순서와 같은 순서
 */
public record ExperimentResult(ExperimentId experimentId, String codeVersion, List<RunResult> runs) {

    public ExperimentResult {
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(codeVersion, "codeVersion");
        runs = List.copyOf(runs);
    }
}
