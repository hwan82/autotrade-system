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
                T0, 2, 0, 0, null, "");
    }

    @Test
    void 쓰고_읽으면_같다() throws IOException {
        ExperimentJobFiles files = ExperimentJobFiles.in(dir);
        ExperimentJobStatus s = running().withProgress(new ExperimentProgress("EXP-20261015-CMB01",
                ExperimentProgress.Phase.RUNNING, 4, 2, 1, 1, 10, 13), T0.plusSeconds(3));
        files.write(s);

        assertThat(files.status("EXP-20261015-CMB01")).isEqualTo(dir.resolve("experiment-state/jobs/EXP-20261015-CMB01.status"));
        assertThat(files.read(files.status("EXP-20261015-CMB01"))).isEqualTo(s);
        assertThat(Files.readString(files.status("EXP-20261015-CMB01"))).startsWith("experiment-job-v2\njob: EXP-20261015-CMB01\n");
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
        assertThatThrownBy(() -> ExperimentJobStatus.decode(ok.replace("experiment-job-v2", "experiment-job-v3")))
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
    void 이전_형식_v1도_읽고_전체_실험_수는_모름이다() {
        // D-097 전 실행 명령이 남긴 상태 파일(작업 폴더에 남은 끝난 작업)이 읽기 실패로 빠지지 않게 한다
        String v2 = running().withExperimentsPlanned(9, T0).encode();
        String v1 = v2.replace("experiment-job-v2", "experiment-job-v1").replace("experiments-planned: 9\n", "");

        ExperimentJobStatus s = ExperimentJobStatus.decode(v1);
        assertThat(s.experimentsPlanned()).isZero();
        assertThat(s.commandPermille()).isEmpty();
        assertThat(s.encode()).startsWith("experiment-job-v2\n").contains("experiments-planned: 0\n");
        // v1에 v2 키가 있거나 v2에 그 키가 빠지면 거부
        assertThatThrownBy(() -> ExperimentJobStatus.decode(v1.replace("message: ", "experiments-planned: 9\nmessage: ")))
                .hasMessageContaining("모르는 키");
        assertThatThrownBy(() -> ExperimentJobStatus.decode(v2.replace("experiments-planned: 9\n", "")))
                .hasMessageContaining("experiments-planned");
        assertThatThrownBy(() -> ExperimentJobStatus.decode(v2.replace("experiments-planned: 9", "experiments-planned: -1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 명령_전체_진행률은_끝난_실험과_지금_실험의_진행률로_센다() {
        ExperimentJobStatus planned = running().withExperimentsPlanned(4, T0);
        assertThat(planned.commandPermille()).hasValue(0);
        assertThat(ExperimentJobStatus.decode(planned.encode())).isEqualTo(planned);

        // 첫 실험 시작: 0, 첫 실험 절반: 1000/2/4 = 125
        ExperimentJobStatus s = planned.withProgress(progress("A", ExperimentProgress.Phase.STARTED, 0), T0);
        assertThat(s.commandPermille()).hasValue(0);
        s = s.withProgress(progress("A", ExperimentProgress.Phase.RUNNING, 10), T0);
        assertThat(s.commandPermille()).hasValue(125);
        // 첫 실험 끝: 250, 둘째 시작: 그대로 250
        s = s.withProgress(progress("A", ExperimentProgress.Phase.FINISHED, 20), T0);
        assertThat(s.commandPermille()).hasValue(250);
        s = s.withProgress(progress("B", ExperimentProgress.Phase.STARTED, 0), T0);
        assertThat(s.commandPermille()).hasValue(250);
        assertThat(s.experimentsStarted()).isEqualTo(2);
        // 넷째까지 끝나면 1000, 알린 수를 넘겨 시작해도 1000에서 멈춤
        s = s.withProgress(progress("B", ExperimentProgress.Phase.FINISHED, 20), T0)
                .withProgress(progress("C", ExperimentProgress.Phase.STARTED, 0), T0)
                .withProgress(progress("C", ExperimentProgress.Phase.FINISHED, 20), T0)
                .withProgress(progress("D", ExperimentProgress.Phase.STARTED, 0), T0)
                .withProgress(progress("D", ExperimentProgress.Phase.FINISHED, 20), T0);
        assertThat(s.commandPermille()).hasValue(1000);
        s = s.withProgress(progress("E", ExperimentProgress.Phase.STARTED, 0), T0)
                .withProgress(progress("E", ExperimentProgress.Phase.RUNNING, 10), T0);
        assertThat(s.commandPermille()).hasValue(1000);
        // 알리지 않은 명령은 비어 있음(조합 실험처럼 한 번만 부르는 명령은 지금 실험 진행률이 곧 전체)
        assertThat(running().withProgress(progress("A", ExperimentProgress.Phase.RUNNING, 10), T0).commandPermille()).isEmpty();
    }

    private static ExperimentProgress progress(String id, ExperimentProgress.Phase phase, long daysDone) {
        int finished = phase == ExperimentProgress.Phase.FINISHED ? 2 : (int) (daysDone / 10);
        return new ExperimentProgress("EXP-20261015-CMB01-" + id, phase, 2, phase == ExperimentProgress.Phase.STARTED ? 0 : 2,
                finished, 0, 10, daysDone);
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
