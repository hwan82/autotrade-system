package com.autotrade.sim.edge;

import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 선정 엣지 일봉 사전 검사 실행 명령(ADR-012 배치표 2b, D-125)의 본체: 등록 파일 {@code experiments/<실험ID>/edge.yaml}을 읽어
 * 수집 일봉 스냅샷 위에서 돌리고 결과를 같은 폴더에 쓴다. 프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}이다
 * ({@code ./gradlew :autotrade-app-experiment:runSelectionEdgeCheck -Pexperiment=<실험ID>}). 그쪽이 장 시간 보호와 감시의
 * 정지 요청 파일을 {@link RunGate}로 넣는다(D-063). 이 클래스에는 {@code main}이 없다.
 *
 * <p>인자: {@code <저장소 루트> <커밋> <dirty true|false> <실험ID> [--snapshots-dir 폴더] [--verify-determinism]}. 스냅샷 폴더
 * 기본값은 설정 폴더 아래 {@code snapshots}. 종료 코드: 0 완료(판정이 탈락·판정 불가여도 0), 1 입력 오류, 2 결정론 불일치,
 * 3 취소 요청·4 정지 확인으로 멈춤(결과 파일을 쓰지 않음).
 */
public final class SelectionEdgeMain {

    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private SelectionEdgeMain() {
    }

    /**
     * @param configDir 설정 폴더(실험 실행 명령이 정한 것)
     * @param gate 정지 확인(D-063). 등록 파일을 읽기 전과 거래일마다 묻는다
     */
    public static int run(String[] args, Path configDir, RunGate gate) {
        if (args.length < 4) {
            System.err.println("인자: <저장소 루트> <커밋> <dirty true|false> <실험ID> [--snapshots-dir 폴더] [--verify-determinism]");
            return 1;
        }
        Path root = Path.of(args[0]);
        CodeVersion code = new CodeVersion(args[1], Boolean.parseBoolean(args[2]));
        String id = args[3];
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            System.err.println("실험 ID 형식이 아닙니다: " + id);
            return 1;
        }
        Path snapshots = configDir.resolve("snapshots");
        boolean verify = false;
        for (int i = 4; i < args.length; i++) {
            switch (args[i]) {
                case "--snapshots-dir" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--snapshots-dir 뒤에 폴더가 필요합니다");
                        return 1;
                    }
                    snapshots = Path.of(args[++i]);
                }
                case "--verify-determinism" -> verify = true;
                default -> {
                    System.err.println("모르는 옵션입니다: " + args[i]);
                    return 1;
                }
            }
        }
        Path dir = root.resolve("experiments").resolve(id);
        if (!Files.isRegularFile(dir.resolve(SelectionEdgeStudy.REGISTRATION))) {
            System.err.println("등록 파일이 없습니다: " + dir.resolve(SelectionEdgeStudy.REGISTRATION)
                    + " (D-125: 실행 전에 등록 파일을 쓰고 커밋한다)");
            return 1;
        }
        try {
            gate.check();
            SelectionEdgeStudy.Outcome out = SelectionEdgeStudy.runAndWrite(dir, root, snapshots, code, verify,
                    SelectionEdgeMain::log, gate);
            log("판정: " + out.verdict().label() + " (results.md 1장)");
            if (Boolean.FALSE.equals(out.deterministic())) {
                log("결정론 확인이 다름입니다. 결과를 쓰지 말고 원인부터 찾으세요");
                return 2;
            }
            log("완료: " + dir);
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[selection-edge] 멈췄습니다(결과 파일을 쓰지 않음): " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("[selection-edge] 실행하지 않았습니다: " + e.getMessage());
            return 1;
        }
    }

    private static void log(String message) {
        System.out.println("[selection-edge] " + message);
    }
}
