package com.autotrade.sim.live;

import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 녹화 사전 검사 명령(ADR-014 F4)의 본체. 프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}
 * ({@code ./gradlew :autotrade-app-experiment:runRecordingPrecheck -Pexperiment=<실험ID>})이고 그쪽이 장 시간 보호·정지 요청
 * 파일·병렬 상한을 {@link RunGate}로 넣는다(D-063·D-085·D-094).
 *
 * <p>인자: {@code <저장소 루트> <커밋> <dirty true|false> <실험ID> [--recordings-dir 폴더] [--snapshots-dir 폴더]
 * [--verify-determinism]}. 폴더 기본값은 설정 폴더 아래 {@value LiveWeeklyMain#RECORDINGS_DIR}, {@value LiveWeeklyMain#SNAPSHOTS_DIR}.
 * 종료 코드: 0 계산을 끝냄(탈락·판정 불가가 있어도 0), 1 입력 오류, 2 실패한 Run이나 결정론 불일치(결과 파일은 씀, 녹화 실험과 같음),
 * 3 취소 요청으로 멈춤(D-089), 4 정지 확인으로 멈춤(3·4는 결과 파일을 쓰지 않음).
 */
public final class PrecheckMain {

    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private PrecheckMain() {
    }

    public static int run(String[] args, Path configDir, RunGate gate) {
        if (args.length < 4) {
            System.err.println("인자: <저장소 루트> <커밋> <dirty true|false> <실험ID> [--recordings-dir 폴더] [--snapshots-dir 폴더] "
                    + "[--verify-determinism]");
            return 1;
        }
        Path root = Path.of(args[0]);
        CodeVersion code = new CodeVersion(args[1], Boolean.parseBoolean(args[2]));
        String id = args[3];
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            System.err.println("실험 ID 형식이 아닙니다(영문·숫자로 시작, 영문·숫자·.·_·- 128자 이내): " + id);
            return 1;
        }
        Path recordings = configDir.resolve(LiveWeeklyMain.RECORDINGS_DIR);
        Path snapshots = configDir.resolve(LiveWeeklyMain.SNAPSHOTS_DIR);
        boolean verify = false;
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--recordings-dir") && i + 1 < args.length) {
                recordings = Path.of(args[++i]);
            } else if (args[i].equals("--snapshots-dir") && i + 1 < args.length) {
                snapshots = Path.of(args[++i]);
            } else if (args[i].equals("--verify-determinism")) {
                verify = true;
            } else {
                System.err.println("모르는 옵션입니다: " + args[i]);
                return 1;
            }
        }
        Path dir = root.resolve("experiments").resolve(id);
        if (!Files.isRegularFile(dir.resolve("experiment.yaml"))) {
            System.err.println("등록 파일이 없습니다: " + dir.resolve("experiment.yaml") + " (실험 전에 등록 파일을 쓰고 커밋한다)");
            return 1;
        }
        try {
            gate.check();
            RecordingPrecheck.Outcome out = RecordingPrecheck.runAndWrite(dir, root, recordings, snapshots, code, verify,
                    PrecheckMain::log, gate);
            out.verdicts().forEach((account, verdict) -> log(account + ": " + verdict));
            if (out.failedRuns() || out.nondeterministic()) {
                log((out.failedRuns() ? "실패한 Run이 있습니다(" + RecordingPrecheck.FILE + " 참고). " : "")
                        + (out.nondeterministic() ? "결정론 확인이 다름입니다." : ""));
                return 2;
            }
            log("완료: " + dir.resolve(RecordingPrecheck.FILE));
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[precheck] 멈췄습니다(결과 파일을 쓰지 않음): " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("[precheck] 실행하지 않았습니다: " + e.getMessage());
            return 1;
        }
    }

    private static void log(String message) {
        System.out.println("[precheck] " + message);
    }
}
