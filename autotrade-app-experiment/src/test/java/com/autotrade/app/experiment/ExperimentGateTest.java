package com.autotrade.app.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실험 실행 명령의 정지 확인(D-063): 감시의 정지 요청 파일과 장 시간 보호. */
class ExperimentGateTest {

    /** 서울 2026-10-10(토) 10:00: 국내·미국 모두 보호 구간 밖. */
    static final String WEEKEND = "2026-10-10T01:00:00Z";
    /** 서울 2026-10-08(목) 10:00: 국내 정규장. */
    static final String KRX_SESSION = "2026-10-08T01:00:00Z";

    @TempDir
    Path config;

    private ExperimentGate gate(String instant, boolean allow) {
        return ExperimentMain.gate(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), config, allow);
    }

    private Path stopRequest() throws IOException {
        Path stop = config.resolve(ExperimentGate.STOP_REQUEST);
        Files.createDirectories(stop.getParent());
        return Files.writeString(stop, "");
    }

    @Test
    void 보호_구간_밖이고_정지_요청이_없으면_계속한다() {
        assertThat(gate(WEEKEND, false).stopReason()).isEmpty();
    }

    @Test
    void 정지_요청_파일은_해제_옵션과_상관없이_멈추고_지우지_않는다() throws IOException {
        Path stop = stopRequest();

        assertThat(gate(WEEKEND, false).stopReason()).get().asString().contains("정지를 요청").contains(stop.toString());
        assertThat(gate(WEEKEND, true).stopReason()).isPresent();
        assertThat(stop).exists();
    }

    @Test
    void 정지_요청이_있는지_판단할_수_없으면_해제_옵션과_상관없이_멈춘다() throws IOException {
        // 부모가 일반 파일이라 exists·notExists 둘 다 false: 확인 실패는 요청 있음으로 본다(PR #52 risk-ops F1)
        Files.writeString(config.resolve("experiment-state"), "file");

        assertThat(gate(WEEKEND, true).stopReason()).get().asString().contains("정지를 요청");
    }

    @Test
    void 국내_장_시간_보호_구간은_멈추고_해제_옵션으로만_푼다() {
        // 서울 08:00 ~ 16:00 보호(수집기와 같은 구간)
        assertThat(gate("2026-10-07T22:59:00Z", false).stopReason()).isEmpty(); // 07:59
        assertThat(gate("2026-10-07T23:00:00Z", false).stopReason()).get().asString().contains("KRX 장 시간 보호 구간"); // 08:00
        assertThat(gate("2026-10-08T07:00:00Z", false).stopReason()).isEmpty(); // 16:00
        assertThat(gate(KRX_SESSION, true).stopReason()).isEmpty();
    }

    @Test
    void 미국_장_시간도_멈춘다() {
        // 뉴욕 2026-10-08(목, EDT) 08:30 = 12:30Z
        assertThat(gate("2026-10-08T12:29:00Z", false).stopReason()).isEmpty();
        assertThat(gate("2026-10-08T12:30:00Z", false).stopReason()).get().asString().contains("US 장 시간 보호 구간");
    }

    @Test
    void 캘린더_수록_범위_밖이면_판단할_수_없어_멈춘다() {
        assertThat(gate("2028-01-05T10:00:00Z", false).stopReason()).get().asString().contains("수록 범위 밖");
    }

    @Test
    void 정지_요청이_있으면_조합_실험을_시작하지_않고_결과를_쓰지_않는다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261011-CMB01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261011-CMB01\n");
        stopRequest();

        int code = ExperimentMain.run(new String[] {"combination", "--allow-during-session", root.toString(), "abc", "false",
                "EXP-20261011-CMB01"}, Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config);

        assertThat(code).isEqualTo(4);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("experiment.yaml");
        }
    }

    @Test
    void 장_시간에는_조합_실험을_시작하지_않는다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261011-CMB01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261011-CMB01\n");

        int code = ExperimentMain.run(new String[] {"combination", root.toString(), "abc", "false", "EXP-20261011-CMB01"},
                Clock.fixed(Instant.parse(KRX_SESSION), ZoneOffset.UTC), config);

        assertThat(code).isEqualTo(4);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void 워크포워드_판정도_정지_요청과_장_시간에는_시작하지_않고_결과를_쓰지_않는다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261020-WF01"));
        Files.writeString(dir.resolve("study.yaml"), "id: EXP-20261020-WF01\n");
        String[] tail = {root.toString(), "abc", "false", "EXP-20261020-WF01"};

        // 장 시간(보호 구간)에는 4
        assertThat(ExperimentMain.run(concat("walkforward", null, tail), Clock.fixed(Instant.parse(KRX_SESSION),
                ZoneOffset.UTC), config)).isEqualTo(4);
        // 정지 요청이 있으면 해제 옵션이 있어도 4
        stopRequest();
        assertThat(ExperimentMain.run(concat("walkforward", "--allow-during-session", tail),
                Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config)).isEqualTo(4);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("study.yaml");
        }
        // 등록 파일이 없으면(정지 요청 없을 때) 입력 오류
        Files.delete(config.resolve(ExperimentGate.STOP_REQUEST));
        assertThat(ExperimentMain.run(new String[] {"walkforward", root.toString(), "abc", "false", "EXP-20261020-NONE"},
                Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config)).isEqualTo(1);
    }

    @Test
    void 정지_요청이_있으면_녹화_실험을_시작하지_않고_결과를_쓰지_않는다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261013-REC01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261013-REC01\n");
        stopRequest();

        int code = ExperimentMain.run(new String[] {"recording", "--allow-during-session", root.toString(), "abc", "false",
                "EXP-20261013-REC01", "--recordings-dir", root.resolve("rec").toString(), "--verify-determinism"},
                Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config);

        assertThat(code).isEqualTo(4);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("experiment.yaml");
        }
    }

    @Test
    void 장_시간에는_녹화_실험을_시작하지_않는다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261013-REC01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261013-REC01\n");

        int code = ExperimentMain.run(new String[] {"recording", root.toString(), "abc", "false", "EXP-20261013-REC01"},
                Clock.fixed(Instant.parse(KRX_SESSION), ZoneOffset.UTC), config);

        assertThat(code).isEqualTo(4);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void 녹화_실험_인자가_틀리면_입력_오류다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261013-REC01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261013-REC01\n");
        Clock weekend = Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC);
        String r = root.toString();

        assertThat(ExperimentMain.run(new String[] {"recording", r, "abc", "false"}, weekend, config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {"recording", r, "abc", "false", "../EXP"}, weekend, config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {"recording", r, "abc", "false", "EXP-20261013-REC01", "--recordings-dir"},
                weekend, config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {"recording", r, "abc", "false", "EXP-20261013-REC01", "--verify"},
                weekend, config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {"recording", r, "abc", "false", "EXP-20261013-NONE"}, weekend, config))
                .isEqualTo(1);
        // 등록 파일이 녹화 입력이 아니면(input 키 없음) 해석 단계의 입력 오류
        assertThat(ExperimentMain.run(new String[] {"recording", r, "abc", "false", "EXP-20261013-REC01", "--recordings-dir",
                root.resolve("rec").toString()}, weekend, config)).isEqualTo(1);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void 실시간_모의_리플레이_확인도_정지_요청과_장_시간에는_시작하지_않는다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261015-LIVE01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261015-LIVE01\n");
        String[] tail = {root.toString(), "abc", "false", "EXP-20261015-LIVE01", "--sessions-dir",
                root.resolve("sessions").toString()};

        assertThat(ExperimentMain.run(concat("live-check", null, tail), Clock.fixed(Instant.parse(KRX_SESSION),
                ZoneOffset.UTC), config)).isEqualTo(4);
        stopRequest();
        assertThat(ExperimentMain.run(concat("live-check", "--allow-during-session", tail),
                Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config)).isEqualTo(4);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("experiment.yaml");
        }
    }

    @Test
    void 실시간_모의_리플레이_확인_인자가_틀리면_입력_오류다(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261015-LIVE01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261015-LIVE01\n");
        Clock weekend = Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC);
        String r = root.toString();

        assertThat(ExperimentMain.run(new String[] {"live-check", r, "abc", "false"}, weekend, config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {"live-check", r, "abc", "false", "../EXP"}, weekend, config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {"live-check", r, "abc", "false", "EXP-20261015-LIVE01", "--x"},
                weekend, config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {"live-check", r, "abc", "false", "EXP-20261015-NONE"}, weekend, config))
                .isEqualTo(1);
        // 등록 파일이 실시간 모의 입력이 아니면 해석 단계의 입력 오류
        assertThat(ExperimentMain.run(new String[] {"live-check", r, "abc", "false", "EXP-20261015-LIVE01"}, weekend,
                config)).isEqualTo(1);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void 정지_요청이_있으면_합성_시세_비교_실험도_시작하지_않는다(@TempDir Path root) throws IOException {
        stopRequest();

        int code = ExperimentMain.run(new String[] {"study", root.toString(), "abc", "false"},
                Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config);

        assertThat(code).isEqualTo(4);
        assertThat(root.resolve("experiments")).doesNotExist();
    }

    @Test
    void 장_시간_보호_해제는_값이_없거나_true일_때만이고_그_밖의_값은_입력_오류다(@TempDir Path root) throws IOException {
        // PR #52 QA F2: -PallowDuringSession=false가 보호를 풀면 안 된다
        Path dir = Files.createDirectories(root.resolve("experiments").resolve("EXP-20261011-CMB01"));
        Files.writeString(dir.resolve("experiment.yaml"), "id: EXP-20261011-CMB01\n");
        Clock session = Clock.fixed(Instant.parse(KRX_SESSION), ZoneOffset.UTC);
        String[] tail = {root.toString(), "abc", "false", "EXP-20261011-CMB01"};

        List<String> bads = List.of("--allow-during-session=false", "--allow-during-session=yes", "--allow-during-sessions");
        List<String> oks = List.of("--allow-during-session", "--allow-during-session=", "--allow-during-session=true");

        // 1) 정지 요청 파일 없음, 장 시간: 옵션이 없으면 4(보호). 해제되면 보호를 지나 등록 파일 해석에서 입력 오류 1
        assertThat(ExperimentMain.run(concat("combination", null, tail), session, config)).isEqualTo(4);
        for (String ok : oks) {
            assertThat(ExperimentMain.run(concat("combination", ok, tail), session, config)).as(ok).isEqualTo(1);
        }

        // 2) 정지 요청 파일 있음: 받아들인 해제 값은 옵션 해석을 지나 문에서 4로 멈추고, 나쁜 값은 문에 닿기 전에 입력 오류 1.
        //    나쁜 값이 해제로 받아들여지면(예전 결함) 여기서 4가 나와 실패한다(PR #52 risk-ops F3)
        stopRequest();
        for (String ok : oks) {
            assertThat(ExperimentMain.run(concat("combination", ok, tail), session, config)).as(ok).isEqualTo(4);
        }
        for (String bad : bads) {
            assertThat(ExperimentMain.run(concat("combination", bad, tail), session, config)).as(bad).isEqualTo(1);
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).hasSize(1);
        }
    }

    private static String[] concat(String command, String option, String[] tail) {
        List<String> all = new java.util.ArrayList<>(List.of(command));
        if (option != null) {
            all.add(option);
        }
        all.addAll(List.of(tail));
        return all.toArray(String[]::new);
    }

    @Test
    void 모르는_명령은_입력_오류다() {
        assertThat(ExperimentMain.run(new String[] {"lab"}, Clock.systemUTC(), config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {}, Clock.systemUTC(), config)).isEqualTo(1);
    }
}
