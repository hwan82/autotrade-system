package com.autotrade.app.experiment.archfixture;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.study.StrategyStudy;
import java.util.List;

/**
 * ArchitectureTest 검증용: 실험 실행 명령이 정지 확인({@code RunGate}) 없는 실행 입구를 부르고, 프로세스 설정을 읽고, 주문 포트를
 * 아는 위반. 운영 코드가 아니며 실행하지 않는다.
 */
public final class RunsWithoutGate {

    private RunsWithoutGate() {}

    static Object runner() {
        return new SimulationExperimentRunner(null, null, null, null, null, null, null, 1, null, null);
    }

    static Object study() {
        return new StrategyStudy(null, List.of(), null, null, null, null, null, s -> { });
    }

    static Object combination() {
        return CombinationExperiment.runAndWrite(null, null, null, null, false, s -> { });
    }

    static LoadedConfig load() {
        return new ConfigLoader().load(ConfigPaths.defaultLocation());
    }

    static Class<?> port() {
        return OrderGateway.class;
    }
}
