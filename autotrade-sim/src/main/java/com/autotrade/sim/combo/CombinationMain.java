package com.autotrade.sim.combo;

import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 조합 실험 실행 명령(D-061)의 본체: 등록 파일 {@code experiments/<실험ID>/experiment.yaml}을 읽어 수집 스냅샷 위에서 돌리고,
 * 결과를 같은 폴더에 쓴다. 프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}이다
 * ({@code ./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=<실험ID>}). 그쪽이 장 시간 보호와 감시의
 * 정지 요청 파일을 {@link RunGate}로 넣는다(D-063). 이 클래스에는 {@code main}이 없어 정지 확인 없이 돌릴 길이 없다.
 *
 * <p>인자: {@code <저장소 루트> <커밋> <dirty true|false> <실험ID> [--snapshots-dir 폴더] [--verify-determinism]}.
 * 스냅샷 폴더 기본값은 설정 폴더 아래 {@code snapshots}(수집기가 쓰는 곳, ADR-008 2.4). 설정 폴더는 {@code ConfigPaths}와
 * 같은 순서로 정한다: 시스템 속성 {@code autotrade.config-dir} → 환경 변수 {@code AUTOTRADE_CONFIG_DIR} → {@code ~/.autotrade}. 종료 코드: 0 완료, 1 입력 오류, 2 Run 실패나 결정론 불일치, 4 정지 확인으로 멈춤(결과 파일을 쓰지 않음, D-063).
 */
public final class CombinationMain {

    /** 실험 ID. 경로 구분자와 {@code ..}가 들어갈 수 없어 {@code experiments/} 밖을 가리키지 못한다. */
    private static final Pattern EXPERIMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private CombinationMain() {
    }

    static int run(String[] args) {
        return run(args, RunGate.NONE);
    }

    /** @param gate 정지 확인(D-063). 등록 파일을 읽기 전과 실험 중에 묻는다 */
    public static int run(String[] args, RunGate gate) {
        if (args.length < 4) {
            System.err.println("인자: <저장소 루트> <커밋> <dirty true|false> <실험ID> [--snapshots-dir 폴더] [--verify-determinism]");
            return 1;
        }
        Path root = Path.of(args[0]);
        CodeVersion code = new CodeVersion(args[1], Boolean.parseBoolean(args[2]));
        String id = args[3];
        if (!EXPERIMENT_ID.matcher(id).matches()) {
            System.err.println("실험 ID 형식이 아닙니다(영문·숫자로 시작, 영문·숫자·.·_·- 128자 이내): " + id);
            return 1;
        }
        Path snapshots = null;
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
        if (snapshots == null) {
            snapshots = configDir().resolve("snapshots");
        }
        Path dir = root.resolve("experiments").resolve(id);
        if (!Files.isRegularFile(dir.resolve("experiment.yaml"))) {
            System.err.println("등록 파일이 없습니다: " + dir.resolve("experiment.yaml")
                    + " (조합 실험 절차 1장: 실험 전에 등록 파일을 쓰고 커밋한다)");
            return 1;
        }
        try {
            gate.check();
            CombinationExperiment.Outcome out = CombinationExperiment.runAndWrite(dir, root, snapshots, code, verify,
                    CombinationMain::log, gate);
            boolean failed = out.report().result().runs().stream().anyMatch(r -> !r.failureReason().isEmpty());
            boolean different = out.files().get("inputs.yaml").contains("determinism: \"다름\"");
            if (failed || different) {
                log((failed ? "실패한 Run이 있습니다(results.md 참고). " : "") + (different ? "결정론 확인이 다름입니다." : ""));
                return 2;
            }
            log("완료: " + dir);
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[combo] 멈췄습니다(결과 파일을 쓰지 않음): " + e.getMessage());
            return 4;
        } catch (RuntimeException e) {
            System.err.println("[combo] 실행하지 않았습니다: " + e.getMessage());
            return 1;
        }
    }

    static Path configDir() {
        String prop = System.getProperty("autotrade.config-dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("AUTOTRADE_CONFIG_DIR");
        return env == null || env.isBlank() ? Path.of(System.getProperty("user.home"), ".autotrade") : Path.of(env);
    }

    private static void log(String message) {
        System.out.println("[combo] " + message);
    }
}
