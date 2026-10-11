package com.autotrade.app.experiment;

import com.autotrade.config.ConfigPaths;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.combo.CombinationMain;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.study.StudyMain;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 실험 실행 명령의 프로세스 진입점(D-063, Q-049). 실전 엔진과 같은 PC에서 도는 단발 명령이라 자원 부족 정지 순서 0번
 * (broker-and-fault-design 5.2)에 들어간다: 감시가 정지 요청 파일을 만들면 시작하지 않고, 실행 중이면 Run 시작 전·거래일마다
 * 확인해 스스로 멈춘다. 장 시간 보호 구간에도 멈춘다({@link ExperimentGate}).
 *
 * <p>인자: {@code <combination|study> [--allow-during-session] <나머지는 CombinationMain·StudyMain 인자>}.
 * Gradle 작업 {@code runCombinationExperiment}·{@code runStrategyStudy}가 채워 넘긴다.
 * 종료 코드: 0 완료, 1 입력 오류, 2 Run 실패나 결정론 불일치(조합 실험), 4 정지 확인으로 멈춤(결과 파일을 쓰지 않음).
 */
public final class ExperimentMain {

    private ExperimentMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC(), ConfigPaths.defaultLocation().runtimeFile().getParent()));
    }

    static int run(String[] args, Clock clock, Path configDir) {
        if (args.length == 0 || !List.of("combination", "study").contains(args[0])) {
            System.err.println("인자: <combination|study> [--allow-during-session] ...");
            return 1;
        }
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        boolean allow = rest.remove("--allow-during-session");
        RunGate gate = gate(clock, configDir, allow);
        String[] forwarded = rest.toArray(String[]::new);
        return switch (args[0]) {
            case "combination" -> CombinationMain.run(forwarded, gate);
            default -> StudyMain.run(forwarded, gate);
        };
    }

    static ExperimentGate gate(Clock clock, Path configDir, boolean allowDuringSession) {
        return new ExperimentGate(clock, List.of(ExchangeCalendar.bundled(Market.KRX), ExchangeCalendar.bundled(Market.US)),
                allowDuringSession, configDir.resolve(ExperimentGate.STOP_REQUEST));
    }
}
