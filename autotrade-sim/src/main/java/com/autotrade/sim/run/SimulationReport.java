package com.autotrade.sim.run;

import com.autotrade.sim.ExperimentResult;
import java.util.List;
import java.util.Objects;

/** 실험 한 번의 요약과 Run별 상세. {@code runs}는 {@code result.runs()}와 같은 순서다. */
public record SimulationReport(ExperimentResult result, List<RunReport> runs) {

    public SimulationReport {
        Objects.requireNonNull(result, "result");
        runs = List.copyOf(runs);
        if (runs.size() != result.runs().size()) {
            throw new IllegalArgumentException("Run 요약과 상세의 개수가 다릅니다");
        }
    }
}
