package com.autotrade.app.lab.experiment;

import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.run.SimulationReport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 대시보드가 읽는 실험 결과 목록(UI-02). 실험 결과는 불변이므로 같은 ID를 다시 등록할 수 없다.
 *
 * <p>지금 구현은 메모리뿐이다({@link InMemoryExperimentCatalog}). DB 저장(ADR-004 4장, experiment·experiment_run·
 * virtual_trade_log·equity_curve)이 생기면 같은 인터페이스의 DB 구현으로 바꾼다. 화면과 REST 응답은 바뀌지 않는다.
 */
public interface ExperimentCatalog {

    /**
     * @throws IllegalArgumentException 명세와 결과가 맞지 않거나(ID, Run 수) 같은 ID가 이미 있을 때
     */
    void register(ExperimentSpec spec, SimulationReport report);

    /** 실험 ID 내림차순(최근 실험이 위). */
    List<Entry> list();

    Optional<Entry> find(ExperimentId id);

    /** 실험 명세와 실행 결과 한 묶음. */
    record Entry(ExperimentSpec spec, SimulationReport report) {
        public Entry {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(report, "report");
            if (!spec.id().equals(report.result().experimentId())) {
                throw new IllegalArgumentException("명세와 결과의 실험 ID가 다릅니다: " + spec.id());
            }
            if (spec.profiles().size() != report.runs().size()) {
                throw new IllegalArgumentException("프로필 수와 Run 수가 다릅니다: " + spec.id());
            }
        }
    }
}
