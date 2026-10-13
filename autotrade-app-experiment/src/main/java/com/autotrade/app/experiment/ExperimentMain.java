package com.autotrade.app.experiment;

import com.autotrade.config.ConfigPaths;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.combo.CombinationMain;
import com.autotrade.sim.recording.RecordingMain;
import com.autotrade.sim.study.StudyMain;
import com.autotrade.sim.walkforward.WalkForwardMain;
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
 * <p>인자: {@code <combination|recording|study|walkforward> [--allow-during-session[=true]] <나머지는 CombinationMain·RecordingMain·StudyMain
 * 인자>}.
 * 해제 옵션은 값이 없거나 {@code true}일 때만 장 시간 보호를 풀고, 그 밖의 값({@code false} 포함)은 입력 오류다. Gradle 속성은
 * {@code gradle.properties}·환경 변수로도 들어와 하루 쓰려던 해제가 조용히 남을 수 있어서, 해제되면 시작 때 그렇게 출력한다.
 * Gradle 작업 {@code runCombinationExperiment}·{@code runRecordingExperiment}·{@code runStrategyStudy}·{@code runWalkForwardStudy}가 채워 넘긴다.
 * 종료 코드: 0 완료, 1 입력 오류, 2 Run 실패나 결정론 불일치(조합·녹화 실험), 4 정지 확인으로 멈춤(결과 파일을 쓰지 않음).
 */
public final class ExperimentMain {

    private ExperimentMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC(), ConfigPaths.defaultLocation().runtimeFile().getParent()));
    }

    static int run(String[] args, Clock clock, Path configDir) {
        if (args.length == 0 || !List.of("combination", "recording", "study", "walkforward").contains(args[0])) {
            System.err.println("인자: <combination|recording|study|walkforward> [--allow-during-session] ...");
            return 1;
        }
        List<String> rest = new ArrayList<>();
        boolean allow = false;
        for (String a : Arrays.asList(args).subList(1, args.length)) {
            if (List.of("--allow-during-session", "--allow-during-session=", "--allow-during-session=true").contains(a)) {
                allow = true;
            } else if (a.startsWith("--allow-during-session")) {
                System.err.println("--allow-during-session은 값이 없거나 true여야 합니다(풀지 않으려면 옵션을 빼세요): " + a);
                return 1;
            } else {
                rest.add(a);
            }
        }
        ExperimentGate gate = gate(clock, configDir, allow);
        System.out.println("[experiment] 정지 요청 파일 확인 위치: " + gate.stopRequest());
        if (allow) {
            System.out.println("[experiment] 장 시간 보호 해제됨(실전 엔진을 끈 날만). 정지 요청 파일은 계속 확인합니다");
        }
        String[] forwarded = rest.toArray(String[]::new);
        return switch (args[0]) {
            case "combination" -> CombinationMain.run(forwarded, gate);
            case "recording" -> RecordingMain.run(forwarded, gate);
            case "walkforward" -> WalkForwardMain.run(forwarded, gate);
            default -> StudyMain.run(forwarded, gate);
        };
    }

    static ExperimentGate gate(Clock clock, Path configDir, boolean allowDuringSession) {
        return new ExperimentGate(clock, List.of(ExchangeCalendar.bundled(Market.KRX), ExchangeCalendar.bundled(Market.US)),
                allowDuringSession, configDir.resolve(ExperimentGate.STOP_REQUEST));
    }
}
