package com.autotrade.sim.live;

import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 6단계 녹화 판정 명령(D-116)의 본체. 프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}
 * ({@code ./gradlew :autotrade-app-experiment:runLiveJudgement -Pexperiment=<실험ID>})이고 그쪽이 장 시간 보호와 정지 요청 파일을
 * {@link RunGate}로 넣는다(D-063).
 *
 * <p>인자: {@code <저장소 루트> <커밋> <dirty true|false> <실험ID> [--sessions-dir 폴더] [--recordings-dir 폴더]}. 폴더 기본값은
 * 설정 폴더 아래 {@value LiveCheckMain#SESSIONS_DIR}, {@value LiveWeeklyMain#RECORDINGS_DIR}.
 * 종료 코드: 0 판정함(후보 0개 포함), 1 입력 오류, 2 무사고 조건 I1~I3이 깨짐(판정 파일은 씀), 3 취소 요청으로 멈춤(D-089), 4 정지
 * 확인으로 멈춤.
 */
public final class LiveJudgeMain {

    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private LiveJudgeMain() {
    }

    public static int run(String[] args, Path configDir, RunGate gate) {
        if (args.length < 4) {
            System.err.println("인자: <저장소 루트> <커밋> <dirty true|false> <실험ID> [--sessions-dir 폴더] [--recordings-dir 폴더]");
            return 1;
        }
        Path root = Path.of(args[0]);
        CodeVersion code = new CodeVersion(args[1], Boolean.parseBoolean(args[2]));
        String id = args[3];
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            System.err.println("실험 ID 형식이 아닙니다(영문·숫자로 시작, 영문·숫자·.·_·- 128자 이내): " + id);
            return 1;
        }
        Path sessions = configDir.resolve(LiveCheckMain.SESSIONS_DIR);
        Path recordings = configDir.resolve(LiveWeeklyMain.RECORDINGS_DIR);
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--sessions-dir") && i + 1 < args.length) {
                sessions = Path.of(args[++i]);
            } else if (args[i].equals("--recordings-dir") && i + 1 < args.length) {
                recordings = Path.of(args[++i]);
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
            LiveJudgement.Outcome out = LiveJudgement.runAndWrite(dir, root, sessions, recordings, code, LiveJudgeMain::log,
                    gate);
            out.verdicts().forEach((account, verdict) -> log(account + ": " + verdict));
            if (out.incident()) {
                log("무사고 조건(I1~I3)이 깨졌습니다. " + LiveJudgement.FILE + " 1장을 보고 알리세요.");
                return 2;
            }
            log("완료: " + dir.resolve(LiveJudgement.FILE));
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[live-judge] 멈췄습니다(결과 파일을 쓰지 않음): " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("[live-judge] 실행하지 않았습니다: " + e.getMessage());
            return 1;
        }
    }

    private static void log(String message) {
        System.out.println("[live-judge] " + message);
    }
}
