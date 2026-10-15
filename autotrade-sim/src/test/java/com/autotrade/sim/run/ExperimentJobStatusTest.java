package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실행 명령과 실험실이 함께 쓰는 작업 상태 파일 코덱(D-089). */
class ExperimentJobStatusTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    @TempDir
    Path dir;

    private static ExperimentJobStatus running() {
        return new ExperimentJobStatus("EXP-20261015-CMB01", "combination", ExperimentJobStatus.State.RUNNING, 4321, T0,
                T0, 2, 0, null, "");
    }

    @Test
    void 쓰고_읽으면_같다() throws IOException {
        ExperimentJobFiles files = ExperimentJobFiles.in(dir);
        ExperimentJobStatus s = running().withProgress(new ExperimentProgress("EXP-20261015-CMB01",
                ExperimentProgress.Phase.RUNNING, 4, 2, 1, 1, 10, 13), T0.plusSeconds(3));
        files.write(s);

        assertThat(files.status("EXP-20261015-CMB01")).isEqualTo(dir.resolve("experiment-state/jobs/EXP-20261015-CMB01.status"));
        assertThat(files.read(files.status("EXP-20261015-CMB01"))).isEqualTo(s);
        assertThat(Files.readString(files.status("EXP-20261015-CMB01"))).startsWith("experiment-job-v1\njob: EXP-20261015-CMB01\n");
        try (var listing = Files.list(files.dir())) {
            assertThat(listing).noneMatch(p -> p.toString().endsWith(".tmp"));
        }
        // 진행 없는 상태도 그대로
        files.write(running());
        assertThat(files.read(files.status("EXP-20261015-CMB01"))).isEqualTo(running());
    }

    @Test
    void 메시지는_한_줄로_자르고_줄을_끼워_넣지_못한다() {
        ExperimentJobStatus s = running().withState(ExperimentJobStatus.State.STOPPED, T0,
                "정지\nstate: COMPLETED\r\n" + "가".repeat(1000));

        assertThat(s.message()).doesNotContain("\n").doesNotContain("\r").hasSize(ExperimentJobStatus.MAX_MESSAGE);
        assertThat(ExperimentJobStatus.decode(s.encode())).isEqualTo(s);
    }

    @Test
    void 형식이_틀리면_읽지_않는다() {
        String ok = running().encode();
        assertThatThrownBy(() -> ExperimentJobStatus.decode(ok.replace("experiment-job-v1", "experiment-job-v2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExperimentJobStatus.decode(ok + "extra: 1\n"))
                .hasMessageContaining("모르는 키");
        assertThatThrownBy(() -> ExperimentJobStatus.decode(ok + "pid: 1\n"))
                .hasMessageContaining("두 번");
        assertThatThrownBy(() -> ExperimentJobStatus.decode(ok.replace("pid: 4321\n", "")))
                .hasMessageContaining("pid");
        assertThatThrownBy(() -> ExperimentJobStatus.decode(ok.replace("job: EXP-20261015-CMB01", "job: ../../x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExperimentJobStatus.decode(ok + "runs-total: 3\n"))
                .hasMessageContaining("일부만");
        assertThatThrownBy(() -> ExperimentJobStatus.decode("x".repeat(ExperimentJobStatus.MAX_BYTES + 1)))
                .hasMessageContaining("너무 큽니다");
    }

    @Test
    void 너무_큰_파일은_끝까지_읽지_않고_거부한다() throws IOException {
        ExperimentJobFiles files = ExperimentJobFiles.in(dir);
        Files.createDirectories(files.dir());
        Path big = files.status("EXP-20261015-CMB01");
        Files.writeString(big, "a".repeat(ExperimentJobStatus.MAX_BYTES * 4), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> files.read(big)).hasMessageContaining("너무 큽니다");
    }

    @Test
    void 실행_중인데_60초_넘게_갱신이_없으면_응답_없음이다() {
        ExperimentJobStatus s = running();
        assertThat(s.stale(T0.plusSeconds(60))).isFalse();
        assertThat(s.stale(T0.plusSeconds(61))).isTrue();
        assertThat(s.withState(ExperimentJobStatus.State.COMPLETED, T0, "").stale(T0.plusSeconds(3600))).isFalse();
    }

    @Test
    void 종료_코드를_끝난_상태로_옮긴다() {
        assertThat(ExperimentJobStatus.State.ofExitCode(0)).isEqualTo(ExperimentJobStatus.State.COMPLETED);
        assertThat(ExperimentJobStatus.State.ofExitCode(1)).isEqualTo(ExperimentJobStatus.State.INPUT_ERROR);
        assertThat(ExperimentJobStatus.State.ofExitCode(2)).isEqualTo(ExperimentJobStatus.State.NEEDS_REVIEW);
        assertThat(ExperimentJobStatus.State.ofExitCode(3)).isEqualTo(ExperimentJobStatus.State.CANCELLED);
        assertThat(ExperimentJobStatus.State.ofExitCode(4)).isEqualTo(ExperimentJobStatus.State.STOPPED);
        assertThat(ExperimentJobStatus.State.ofExitCode(-1)).isEqualTo(ExperimentJobStatus.State.FAILED);
    }

    @Test
    void 작업_파일_이름은_실험_ID_형식만_받는다() {
        ExperimentJobFiles files = ExperimentJobFiles.in(dir);
        assertThatThrownBy(() -> files.cancelRequest("../stop-request")).isInstanceOf(IllegalArgumentException.class);
        assertThat(files.cancelRequest("EXP-1")).hasFileName("EXP-1.cancel-request");
    }
}
