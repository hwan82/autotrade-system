package com.autotrade.sim.live;

import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 6단계 주간 점검 명령(D-088)의 본체. 프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}
 * ({@code ./gradlew :autotrade-app-experiment:runLiveWeeklyCheck -Pexperiment=<실험ID>})이고 그쪽이 장 시간 보호와 정지 요청 파일을
 * {@link RunGate}로 넣는다(D-063).
 *
 * <p>인자: {@code <저장소 루트> <커밋> <dirty true|false> <실험ID> [--to YYYY-MM-DD] [--sessions-dir 폴더]
 * [--recordings-dir 폴더]}. 폴더 기본값은 설정 폴더 아래 {@value LiveCheckMain#SESSIONS_DIR}와 {@value #RECORDINGS_DIR}.
 * 종료 코드: 0 설명되지 않는 차이 없음, 1 입력 오류, 2 설명되지 않는 차이 있음(전달·기록 경로 확인 필요), 4 정지 확인으로 멈춤.
 */
public final class LiveWeeklyMain {

    /** 설정 폴더 안 게이트웨이 녹화 폴더 이름(ADR-003 2.3). */
    public static final String RECORDINGS_DIR = "recordings";
    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private LiveWeeklyMain() {
    }

    /**
     * @param configDir 설정 폴더(세션·녹화 폴더 기본값을 정한다)
     */
    public static int run(String[] args, Path configDir, RunGate gate) {
        if (args.length < 4) {
            System.err.println("인자: <저장소 루트> <커밋> <dirty true|false> <실험ID> [--to YYYY-MM-DD] [--sessions-dir 폴더] "
                    + "[--recordings-dir 폴더]");
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
        Path recordings = configDir.resolve(RECORDINGS_DIR);
        Optional<LocalDate> to = Optional.empty();
        for (int i = 4; i < args.length; i++) {
            if (args[i].equals("--sessions-dir") && i + 1 < args.length) {
                sessions = Path.of(args[++i]);
            } else if (args[i].equals("--recordings-dir") && i + 1 < args.length) {
                recordings = Path.of(args[++i]);
            } else if (args[i].equals("--to") && i + 1 < args.length) {
                try {
                    to = Optional.of(LocalDate.parse(args[++i]));
                } catch (DateTimeParseException e) {
                    System.err.println("--to는 YYYY-MM-DD 날짜여야 합니다: " + args[i]);
                    return 1;
                }
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
            LiveWeeklyCheck.Outcome out = LiveWeeklyCheck.runAndWrite(dir, root, sessions, recordings, to, code,
                    LiveWeeklyMain::log, gate);
            if (out.defect()) {
                log("설명되지 않는 차이가 있습니다. weekly/" + out.to() + ".md의 \"설명 없음\" 칸을 보고 알리세요.");
                return 2;
            }
            log("완료: " + out.from() + " ~ " + out.to() + (out.accountsCompared() ? "" : " (계좌 대조는 하지 않음, 보고서 참고)"));
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[live-weekly] 멈췄습니다(결과 파일을 쓰지 않음): " + e.getMessage());
            return 4;
        } catch (RuntimeException e) {
            System.err.println("[live-weekly] 실행하지 않았습니다: " + e.getMessage());
            return 1;
        }
    }

    private static void log(String message) {
        System.out.println("[live-weekly] " + message);
    }
}
