package com.autotrade.app.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
        assertThat(gate("2027-01-05T10:00:00Z", false).stopReason()).get().asString().contains("수록 범위 밖");
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
    void 정지_요청이_있으면_합성_시세_비교_실험도_시작하지_않는다(@TempDir Path root) throws IOException {
        stopRequest();

        int code = ExperimentMain.run(new String[] {"study", root.toString(), "abc", "false"},
                Clock.fixed(Instant.parse(WEEKEND), ZoneOffset.UTC), config);

        assertThat(code).isEqualTo(4);
        assertThat(root.resolve("experiments")).doesNotExist();
    }

    @Test
    void 모르는_명령은_입력_오류다() {
        assertThat(ExperimentMain.run(new String[] {"lab"}, Clock.systemUTC(), config)).isEqualTo(1);
        assertThat(ExperimentMain.run(new String[] {}, Clock.systemUTC(), config)).isEqualTo(1);
    }
}
