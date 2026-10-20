package com.autotrade.sim.live;

import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 실시간 모의 리플레이 확인 명령(D-076)의 본체. 프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}
 * ({@code ./gradlew :autotrade-app-experiment:runLiveReplayCheck -Pexperiment=<실험ID>})이고 그쪽이 장 시간 보호와 정지 요청 파일을
 * {@link RunGate}로 넣는다(D-063).
 *
 * <p>인자: {@code <저장소 루트> <커밋> <dirty true|false> <실험ID> [--sessions-dir 폴더] [--snapshots-dir 폴더]}. 세션 폴더
 * 기본값은 설정 폴더 아래 {@value #SESSIONS_DIR}(실험실이 쓰는 곳), 스냅샷 폴더는 {@value LiveWeeklyMain#SNAPSHOTS_DIR}(선정 조합
 * 계좌의 재계산, D-124). 종료 코드: 0 같음, 1 입력 오류(적용 기록과 선정 결과 파일이 맞지 않음 포함), 2 다름(실시간 경로 결함)이나
 * 실패한 Run이나 선정 재계산이 다름, 3 취소 요청으로 멈춤(D-089), 4 정지 확인으로 멈춤.
 */
public final class LiveCheckMain {

    /** 설정 폴더 안 세션 폴더 이름. 실험실과 같아야 한다. */
    public static final String SESSIONS_DIR = "live-sessions";
    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private LiveCheckMain() {
    }

    /**
     * @param configDir 설정 폴더(세션 폴더 기본값을 정한다)
     */
    public static int run(String[] args, Path configDir, RunGate gate) {
        if (args.length < 4) {
            System.err.println("인자: <저장소 루트> <커밋> <dirty true|false> <실험ID> [--sessions-dir 폴더] [--snapshots-dir 폴더]");
            return 1;
        }
        Path root = Path.of(args[0]);
        CodeVersion code = new CodeVersion(args[1], Boolean.parseBoolean(args[2]));
        String id = args[3];
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            System.err.println("실험 ID 형식이 아닙니다(영문·숫자로 시작, 영문·숫자·.·_·- 128자 이내): " + id);
            return 1;
        }
        Path sessions = configDir.resolve(SESSIONS_DIR);
        Path snapshots = configDir.resolve(LiveWeeklyMain.SNAPSHOTS_DIR);
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--sessions-dir") && i + 1 < args.length) {
                sessions = Path.of(args[++i]);
            } else if (args[i].equals("--snapshots-dir") && i + 1 < args.length) {
                snapshots = Path.of(args[++i]);
            } else {
                System.err.println("모르는 옵션입니다: " + args[i]);
                return 1;
            }
        }
        Path dir = root.resolve("experiments").resolve(id);
        if (!Files.isRegularFile(dir.resolve("experiment.yaml"))) {
            System.err.println("등록 파일이 없습니다: " + dir.resolve("experiment.yaml"));
            return 1;
        }
        try {
            gate.check();
            LiveReplayCheck.Outcome out = LiveReplayCheck.runAndWrite(dir, root, sessions, snapshots, code,
                    LiveCheckMain::log, gate);
            boolean failed = out.replay().result().runs().stream().anyMatch(r -> !r.failureReason().isEmpty());
            if (!out.same() || failed || !out.selectionsRecomputed()) {
                log((out.same() ? "" : "실시간 결과와 리플레이 결과가 다릅니다(실시간 경로 결함). ")
                        + (failed ? "실패한 Run이 있습니다(results.md 참고). " : "")
                        + (out.selectionsRecomputed() ? "" : "선정을 다시 계산한 결과가 고정 파일과 다릅니다(계산 코드나 입력 "
                                + "스냅샷이 바뀜, inputs.yaml selection-recompute 참고)."));
                return 2;
            }
            log("완료: 실시간 결과와 리플레이 결과가 같습니다. " + dir);
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[live-check] 멈췄습니다(결과 파일을 쓰지 않음): " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("[live-check] 실행하지 않았습니다: " + e.getMessage());
            return 1;
        }
    }

    private static void log(String message) {
        System.out.println("[live-check] " + message);
    }
}
