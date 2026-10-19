package com.autotrade.app.experiment;

import com.autotrade.config.ConfigPaths;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.combo.CombinationMain;
import com.autotrade.sim.live.LiveCheckMain;
import com.autotrade.sim.live.LiveJudgeMain;
import com.autotrade.sim.live.LiveWeeklyMain;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.recording.RecordingMain;
import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentLimits;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.study.StudyMain;
import com.autotrade.sim.walkforward.WalkForwardMain;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * 실험 실행 명령의 프로세스 진입점(D-063, Q-049). 실전 엔진과 같은 PC에서 도는 단발 명령이라 자원 부족 정지 순서 0번
 * (broker-and-fault-design 5.2)에 들어간다: 감시가 정지 요청 파일을 만들면 시작하지 않고, 실행 중이면 Run 시작 전·거래일마다
 * 확인해 스스로 멈춘다. 장 시간 보호 구간에도 멈춘다({@link ExperimentGate}).
 *
 * <p>인자: {@code <combination|recording|study|walkforward|live-check|live-weekly|live-judge> [--allow-during-session[=true]]
 * [--max-parallel-runs=N] <나머지는 CombinationMain·
 * RecordingMain·StudyMain·WalkForwardMain·LiveCheckMain·LiveWeeklyMain·LiveJudgeMain 인자>}.
 * 해제 옵션은 값이 없거나 {@code true}일 때만 장 시간 보호를 풀고, 그 밖의 값({@code false} 포함)은 입력 오류다. Gradle 속성은
 * {@code gradle.properties}·환경 변수로도 들어와 하루 쓰려던 해제가 조용히 남을 수 있어서, 해제되면 시작 때 그렇게 출력한다.
 * Gradle 작업 {@code runCombinationExperiment}·{@code runRecordingExperiment}·{@code runStrategyStudy}·{@code runWalkForwardStudy}·
 * {@code runLiveReplayCheck}·{@code runLiveWeeklyCheck}·{@code runLiveJudgement}가 채워 넘긴다.
 * 종료 코드: 0 완료, 1 입력 오류(같은 실험이 이미 실행 중 포함), 2 Run 실패나 결정론 불일치(조합·녹화 실험), 3 실험실의 취소
 * 요청으로 멈춤, 4 정지 확인으로 멈춤(3·4는 결과 파일을 쓰지 않음).
 *
 * <p>진행 보고·취소·자원 상한(SIM-14, D-089): 실험 ID마다 {@link ExperimentJob}이 잠금을 잡고
 * {@code <설정 폴더>/experiment-state/jobs/<실험ID>.status}에 진행 상태를 쓰며, 같은 폴더의 취소 요청 파일을 확인한다. 동시에
 * 도는 Run 수는 등록 파일의 {@code parallelism}과 병렬 상한 중 작은 쪽이다. 병렬 상한은 {@code --max-parallel-runs}(Gradle
 * {@code -PmaxParallelRuns})가 있으면 그 값, 없으면 실험실이 설정 화면의 {@code lab.max-parallel-runs}로 쓴 상한 파일
 * {@code <설정 폴더>/experiment-state/limits}({@link ExperimentLimits}, D-094), 그 파일도 없으면 기본 4다.
 */
public final class ExperimentMain {

    /** 병렬 Run 상한 옵션. */
    static final String MAX_PARALLEL_OPTION = "--max-parallel-runs=";
    /** 병렬 Run 상한 기본값. 실험실 설정 키 {@code lab.max-parallel-runs}의 기본값과 같다 {@code [가정]}(config-keys.md). */
    static final int DEFAULT_MAX_PARALLEL_RUNS = 4;

    private ExperimentMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC(), ConfigPaths.defaultLocation().runtimeFile().getParent()));
    }

    static int run(String[] args, Clock clock, Path givenConfigDir) {
        return run(args, clock, givenConfigDir, ExperimentMain::dispatch);
    }

    /** 실험 본체. 시험이 가짜 본체를 넣어 {@link #run}이 {@link #runControlled}를 거치는지 본다(PR #113 risk-ops 3차 R9). */
    @FunctionalInterface
    interface Body {
        int run(String command, String[] forwarded, Path configDir, RunGate gate);
    }

    static int run(String[] args, Clock clock, Path givenConfigDir, Body body) {
        // 상대 경로나 ..가 섞여도 상태 파일 메시지에서 설정 폴더 경로를 가릴 수 있게 한 번 정규화한다(PR #113 architect R5·risk-ops R3)
        Path configDir = givenConfigDir.toAbsolutePath().normalize();
        if (args.length == 0 || !List.of("combination", "recording", "study", "walkforward", "live-check", "live-weekly",
                "live-judge")
                .contains(args[0])) {
            System.err.println(
                    "인자: <combination|recording|study|walkforward|live-check|live-weekly|live-judge> [--allow-during-session] ...");
            return 1;
        }
        List<String> rest = new ArrayList<>();
        boolean allow = false;
        Integer optionMaxParallelRuns = null;
        for (String a : Arrays.asList(args).subList(1, args.length)) {
            if (a.startsWith(MAX_PARALLEL_OPTION)) {
                Integer n = maxParallelRuns(a);
                if (n == null) {
                    System.err.println("--max-parallel-runs는 1~64여야 합니다: " + a);
                    return 1;
                }
                optionMaxParallelRuns = n;
            } else if (List.of("--allow-during-session", "--allow-during-session=", "--allow-during-session=true").contains(a)) {
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
        MaxParallel cap;
        try {
            cap = maxParallelRuns(optionMaxParallelRuns, configDir);
        } catch (IllegalArgumentException e) {
            // 사용자가 실험실에서 낮춰 둔 상한을 기본값으로 조용히 넘지 않는다(D-094)
            System.err.println("[experiment] 병렬 상한 파일(" + ExperimentLimits.file(configDir) + ")을 읽을 수 없어 시작하지 않습니다: "
                    + e.getMessage() + ". 실험실 설정 화면에서 다시 저장하거나 -PmaxParallelRuns=N으로 주세요");
            // 정지 요청이 있으면 그 이유와 종료 코드 4가 먼저다(PR #113 risk-ops F1과 같은 원칙)
            return stoppedOr(gate, 1);
        }
        int maxParallelRuns = cap.value();
        String[] forwarded = rest.toArray(String[]::new);
        String job = jobId(args[0], rest);
        if (job == null) {
            // 잠금·상태 파일·병렬 상한 없이 본체를 돌리지 않는다(PR #113 architect R4)
            System.err.println("[experiment] 실험 ID 인자가 없거나 실험 ID 형식이 아닙니다(저장소, 커밋, dirty 다음 네 번째 인자)");
            return 1;
        }
        ExperimentJob control;
        try {
            control = ExperimentJob.start(job, args[0], gate, maxParallelRuns, clock, ExperimentJobFiles.in(configDir),
                    ProcessHandle.current().pid());
        } catch (ExperimentJob.AlreadyRunningException e) {
            System.err.println("[experiment] " + e.getMessage());
            return stoppedOr(gate, 1);
        } catch (IOException e) {
            System.err.println("[experiment] 작업 폴더(" + ExperimentJobFiles.in(configDir).dir()
                    + ")를 쓸 수 없어 시작하지 않습니다: " + e.getClass().getSimpleName());
            // 디스크 고갈 등으로 작업 폴더를 못 쓸 때도 정지 요청·장 시간 보호는 종료 코드 4와 이유로 알린다(장애표 #10,
            // PR #113 risk-ops F1). 감시가 남긴 정지 요청 파일을 사람이 알아채는 단서가 이 출력이다
            return stoppedOr(gate, 1);
        }
        System.out.println("[experiment] 진행 상태 파일: " + control.statusFile() + ", 병렬 Run 상한 "
                + maxParallelRuns + "(" + cap.source() + ", 등록 파일 parallelism과 작은 쪽)");
        return runControlled(control, runGate -> body.run(args[0], forwarded, configDir, runGate));
    }

    /**
     * 본체를 작업 제어 아래에서 돌리고 끝난 상태를 남긴다. 취소로 끝났으면 정지 확인을 한 번 더 본다({@link
     * ExperimentJob#afterCancel}, 독립 QA PR #113 R1).
     */
    static int runControlled(ExperimentJob control, ToIntFunction<RunGate> body) {
        int code = -1;
        try {
            code = control.afterCancel(body.applyAsInt(control));
            return code;
        } finally {
            control.finish(code);
        }
    }

    /** 정지 확인이 멈추라고 하면 본체와 같은 문구·종료 코드 4, 아니면 {@code otherwise}. */
    private static int stoppedOr(ExperimentGate gate, int otherwise) {
        Optional<String> stop = gate.stopReason();
        if (stop.isPresent()) {
            System.err.println("[experiment] 멈췄습니다(결과 파일을 쓰지 않음): " + stop.get());
            return 4;
        }
        return otherwise;
    }

    private static int dispatch(String command, String[] forwarded, Path configDir, RunGate gate) {
        return switch (command) {
            case "combination" -> CombinationMain.run(forwarded, gate);
            case "recording" -> RecordingMain.run(forwarded, gate);
            case "walkforward" -> WalkForwardMain.run(forwarded, gate);
            case "live-check" -> LiveCheckMain.run(forwarded, configDir, gate);
            case "live-weekly" -> LiveWeeklyMain.run(forwarded, configDir, gate);
            case "live-judge" -> LiveJudgeMain.run(forwarded, configDir, gate);
            default -> StudyMain.run(forwarded, gate);
        };
    }

    /** 작업 ID = 실험 ID. 합성 시세 비교는 고정 ID, 나머지는 네 번째 위치 인자(저장소, 커밋, dirty 다음). 없거나 틀리면 null. */
    static String jobId(String command, List<String> rest) {
        if (command.equals("study")) {
            return StudyMain.ID.value();
        }
        if (rest.size() < 4) {
            return null;
        }
        try {
            return new ExperimentId(rest.get(3)).value();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 이 실행이 쓸 병렬 Run 상한과 그 출처(시작 출력용). */
    record MaxParallel(int value, String source) {
    }

    /**
     * 병렬 Run 상한(SIM-14, D-089·D-094). 옵션이 있으면 옵션, 없으면 실험실이 쓴 상한 파일, 그것도 없으면
     * {@value #DEFAULT_MAX_PARALLEL_RUNS}. 이 명령은 설정 로더와 {@code runtime-config.json}을 쓰지 않는다(ArchUnit
     * {@code EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG}, D-063). 상한 파일이 있는데 읽지 못하거나 형식이 틀리면
     * {@link IllegalArgumentException}(입력 오류).
     */
    static MaxParallel maxParallelRuns(Integer option, Path configDir) {
        if (option != null) {
            return new MaxParallel(option, "-PmaxParallelRuns");
        }
        Optional<ExperimentLimits> limits;
        try {
            limits = ExperimentLimits.read(configDir);
        } catch (IOException e) {
            throw new IllegalArgumentException("읽기 실패 " + e.getClass().getSimpleName(), e);
        }
        return limits.map(l -> new MaxParallel(l.maxParallelRuns(), "실험실 설정 lab.max-parallel-runs, " + l.updatedAt() + " 저장"))
                .orElseGet(() -> new MaxParallel(DEFAULT_MAX_PARALLEL_RUNS, "기본값, 실험실 상한 파일 없음"));
    }

    /**
     * 병렬 Run 상한 옵션 {@code --max-parallel-runs=N}(SIM-14, D-089). 1~64. 형식이 틀리면 null.
     */
    static Integer maxParallelRuns(String option) {
        String v = option.substring(MAX_PARALLEL_OPTION.length());
        try {
            int n = Integer.parseInt(v);
            return n >= 1 && n <= 64 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static ExperimentGate gate(Clock clock, Path configDir, boolean allowDuringSession) {
        return new ExperimentGate(clock, List.of(ExchangeCalendar.bundled(Market.KRX), ExchangeCalendar.bundled(Market.US)),
                allowDuringSession, configDir.resolve(ExperimentGate.STOP_REQUEST));
    }
}
