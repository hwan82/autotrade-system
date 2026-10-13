package com.autotrade.sim.run;

import com.autotrade.domain.market.DataGap;
import com.autotrade.sim.ExperimentResult;
import java.util.List;
import java.util.Objects;

/**
 * 실험 한 번의 요약과 Run별 상세. {@code runs}는 {@code result.runs()}와 같은 순서다.
 *
 * @param dataGaps 실험 기간 입력 시세의 공백 표시(sequence 순). 모든 Run이 같은 입력을 보므로 실험에 하나다(D-068).
 *     이 구간에 걸친 체결·신호는 공백 동안의 시세를 모른 채 나온 것이다
 */
public record SimulationReport(ExperimentResult result, List<RunReport> runs, List<DataGap> dataGaps) {

    public SimulationReport {
        Objects.requireNonNull(result, "result");
        runs = List.copyOf(runs);
        dataGaps = List.copyOf(dataGaps);
        if (runs.size() != result.runs().size()) {
            throw new IllegalArgumentException("Run 요약과 상세의 개수가 다릅니다");
        }
    }

    /** 공백 표시가 없는 실험(결과 묶음 읽기, 테스트). */
    public SimulationReport(ExperimentResult result, List<RunReport> runs) {
        this(result, runs, List.of());
    }
}
