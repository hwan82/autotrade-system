package com.autotrade.app.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.sim.run.ExperimentCancelledException;
import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentJobStatus;
import com.autotrade.sim.run.ExperimentLimits;
import com.autotrade.sim.run.ExperimentProgress;
import com.autotrade.sim.run.ExperimentStoppedException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실험 실행 명령의 작업 제어(D-089): 단일 실행 잠금, 진행 상태 파일, 취소 요청, 병렬 상한. */
class ExperimentJobTest {

    private static final String JOB = "EXP-20261015-CMB01";
    private static final Instant WEEKEND = Instant.parse(ExperimentGateTest.WEEKEND);

    @TempDir
    Path config;

    /** 테스트가 옮기는 시계. */
    static final class MovingClock extends Clock {

        final AtomicReference<Instant> now;

        MovingClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private ExperimentJobFiles files() {
        return ExperimentJobFiles.in(config);
    }

    private ExperimentJob start(Clock clock) throws Exception {
        return ExperimentJob.start(JOB, "combination", ExperimentMain.gate(clock, config, false), 3, clock, files(), 77,
                Duration.ofHours(1));
    }

    private ExperimentJobStatus status() throws IOException {
        return files().read(files().status(JOB));
    }

    private static ExperimentProgress progress(ExperimentProgress.Phase phase, long daysDone) {
        return new ExperimentProgress(JOB, phase, 2, 2, 0, 0, 10, daysDone);
    }

    @Test
    void 시작하면_실행_중_상태를_쓰고_같은_실험은_두_번_시작하지_않는다() throws Exception {
        MovingClock clock = new MovingClock(WEEKEND);
        ExperimentJob job = start(clock);

        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.RUNNING);
        assertThat(status().pid()).isEqualTo(77);
        assertThat(status().maxParallelRuns()).isEqualTo(3);
        assertThat(job.maxParallelRuns()).isEqualTo(3);
        assertThatThrownBy(() -> start(clock)).isInstanceOf(ExperimentJob.AlreadyRunningException.class)
                .hasMessageContaining("이미 실행 중");

        job.finish(0);
        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.COMPLETED);
        start(clock).finish(0); // 끝나면 잠금이 풀려 다시 시작할 수 있다
    }

    @Test
    void 지난_실행에서_남은_취소_요청은_새_실행을_멈추지_않는다() throws Exception {
        Files.createDirectories(files().dir());
        Files.writeString(files().cancelRequest(JOB), "");

        ExperimentJob job = start(new MovingClock(WEEKEND));

        assertThat(files().cancelRequest(JOB)).doesNotExist();
        job.check();
        job.finish(0);
    }

    @Test
    void 취소_요청이_있으면_취소로_끝나고_요청_파일을_지운다() throws Exception {
        ExperimentJob job = start(new MovingClock(WEEKEND));
        Files.writeString(files().cancelRequest(JOB), "");

        assertThat(job.stopReason()).get().asString().contains("취소");
        assertThatThrownBy(job::check).isInstanceOf(ExperimentCancelledException.class).hasMessageContaining("취소");
        job.finish(3);

        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.CANCELLED);
        assertThat(status().message()).contains("취소").contains("<설정 폴더>").doesNotContain(config.toString());
        assertThat(files().cancelRequest(JOB)).doesNotExist();
    }

    @Test
    void 감시의_정지_요청이_취소보다_먼저다() throws Exception {
        ExperimentJob job = start(new MovingClock(WEEKEND));
        Files.writeString(files().cancelRequest(JOB), "");
        Path stop = config.resolve(ExperimentGate.STOP_REQUEST);
        Files.writeString(stop, "");

        assertThatThrownBy(job::check).isExactlyInstanceOf(ExperimentStoppedException.class)
                .hasMessageContaining("정지를 요청");
        job.finish(4);

        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.STOPPED);
        assertThat(status().message()).contains("정지를 요청").contains("<설정 폴더>").doesNotContain(config.toString());
        assertThat(stop).exists(); // 정지 요청 파일은 감시만 지운다(D-058)
    }

    @Test
    void 취소로_멈추는_사이_정지_요청이_생기면_종료_코드_4와_정지_이유를_남긴다() throws Exception {
        // 독립 QA PR #113 R1(탐침 P1): 실행기는 처음 받은 취소 예외만 올리므로 끝날 때 정지 확인을 한 번 더 본다
        ExperimentJob job = start(new MovingClock(WEEKEND));
        Files.writeString(files().cancelRequest(JOB), "");
        assertThatThrownBy(job::check).isInstanceOf(ExperimentCancelledException.class);
        Files.writeString(config.resolve(ExperimentGate.STOP_REQUEST), "");

        int code = job.afterCancel(3);
        job.finish(code);

        assertThat(code).isEqualTo(4);
        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.STOPPED);
        assertThat(status().message()).contains("정지를 요청").doesNotContain(config.toString());
    }

    @Test
    void 실행_명령은_취소로_끝난_본체도_정지_요청이_있으면_종료_코드_4로_끝낸다() throws Exception {
        // architect 2차 R7: afterCancel이 실행 명령 경로에 실제로 연결돼 있는지 본체부터 끝까지 본다
        ExperimentJob job = start(new MovingClock(WEEKEND));
        int code = ExperimentMain.runControlled(job, gate -> {
            try {
                Files.writeString(files().cancelRequest(JOB), "");
                gate.check();
                return 0;
            } catch (ExperimentCancelledException e) {
                try {
                    Files.writeString(config.resolve(ExperimentGate.STOP_REQUEST), "");
                } catch (IOException io) {
                    throw new UncheckedIOException(io);
                }
                return e.exitCode();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(code).isEqualTo(4);
        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.STOPPED);
        assertThat(status().message()).contains("정지를 요청");
        assertThat(files().cancelRequest(JOB)).doesNotExist();
    }

    @Test
    void 실행_명령_진입점은_본체를_작업_제어_아래에서_돌린다(@TempDir Path repo) throws Exception {
        // risk-ops 3차 R9: run이 runControlled를 거치지 않으면(afterCancel을 건너뛰면) 취소 뒤 정지 요청이 4로 바뀌지 않는다
        List<String> seen = new java.util.ArrayList<>();
        int code = ExperimentMain.run(new String[] {"combination", "--max-parallel-runs=2", repo.toString(), "abc", "false",
                JOB}, Clock.fixed(WEEKEND, ZoneOffset.UTC), config, (command, forwarded, configDir, gate) -> {
                    seen.add(command + " " + String.join(" ", forwarded) + " 상한 " + gate.maxParallelRuns());
                    try {
                        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.RUNNING);
                        Files.writeString(files().cancelRequest(JOB), "");
                        gate.check();
                        return 0;
                    } catch (ExperimentCancelledException e) {
                        try {
                            Files.writeString(config.resolve(ExperimentGate.STOP_REQUEST), "");
                        } catch (IOException io) {
                            throw new UncheckedIOException(io);
                        }
                        return e.exitCode();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });

        assertThat(seen).containsExactly("combination " + repo + " abc false " + JOB + " 상한 2");
        assertThat(code).isEqualTo(4);
        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.STOPPED);
        assertThat(status().message()).contains("정지를 요청");
        assertThat(files().cancelRequest(JOB)).doesNotExist();
        // 잠금이 풀려 같은 실험을 다시 시작할 수 있다
        Files.delete(config.resolve(ExperimentGate.STOP_REQUEST));
        start(Clock.fixed(WEEKEND, ZoneOffset.UTC)).finish(0);
    }

    @Test
    void 정지_요청이_없으면_취소는_취소로_남고_다른_종료_코드는_그대로다() throws Exception {
        ExperimentJob job = start(new MovingClock(WEEKEND));
        assertThat(job.afterCancel(0)).isZero();
        assertThat(job.afterCancel(2)).isEqualTo(2);
        assertThat(job.afterCancel(3)).isEqualTo(3);
        Files.writeString(config.resolve(ExperimentGate.STOP_REQUEST), "");
        // 완료·검토 필요는 결과를 이미 썼으므로 정지로 바꾸지 않는다
        assertThat(job.afterCancel(0)).isZero();
        job.finish(0);
    }

    @Test
    void 진행_중_보고는_1초에_한_번까지만_쓰고_단계가_바뀌면_바로_쓴다() throws Exception {
        MovingClock clock = new MovingClock(WEEKEND);
        ExperimentJob job = start(clock);

        job.onProgress(new ExperimentProgress(JOB, ExperimentProgress.Phase.STARTED, 2, 0, 0, 0, 10, 0));
        assertThat(status().experimentsStarted()).isEqualTo(1);
        assertThat(status().progress().phase()).isEqualTo(ExperimentProgress.Phase.STARTED);

        clock.advance(Duration.ofMillis(500));
        job.onProgress(progress(ExperimentProgress.Phase.RUNNING, 1));
        assertThat(status().progress().phase()).isEqualTo(ExperimentProgress.Phase.STARTED); // 아직 안 씀
        clock.advance(Duration.ofMillis(500));
        job.onProgress(progress(ExperimentProgress.Phase.RUNNING, 2));
        assertThat(status().progress().daysDone()).isEqualTo(2);

        job.onProgress(new ExperimentProgress(JOB, ExperimentProgress.Phase.FINISHED, 2, 2, 2, 0, 10, 20));
        assertThat(status().progress().phase()).isEqualTo(ExperimentProgress.Phase.FINISHED);
        job.finish(0);
        assertThat(status().progress().daysDone()).isEqualTo(20); // 끝난 상태에도 마지막 진행이 남는다
    }

    @Test
    void 실행_중에는_갱신_시각을_주기적으로_다시_쓴다() throws Exception {
        MovingClock clock = new MovingClock(WEEKEND);
        ExperimentJob job = ExperimentJob.start(JOB, "combination", ExperimentMain.gate(clock, config, false), 3,
                clock, files(), 77, Duration.ofMillis(20));
        clock.advance(Duration.ofSeconds(30));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!status().updatedAt().equals(clock.instant()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(status().updatedAt()).isEqualTo(clock.instant());
        job.finish(0);
    }

    @Test
    void 상태_파일을_쓰지_못해도_실험은_계속한다() throws Exception {
        MovingClock clock = new MovingClock(WEEKEND);
        ExperimentJob job = start(clock);
        Path status = files().status(JOB);
        Files.delete(status);
        Files.createDirectories(status); // 바꿔 넣을 수 없는 자리

        job.onProgress(new ExperimentProgress(JOB, ExperimentProgress.Phase.STARTED, 2, 0, 0, 0, 10, 0));
        job.check();
        job.finish(0);
    }

    @Test
    void 닫기만_하면_예상_못_한_오류로_끝난다() throws Exception {
        try (ExperimentJob job = start(new MovingClock(WEEKEND))) {
            assertThat(job.status().state()).isEqualTo(ExperimentJobStatus.State.RUNNING);
        }
        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.FAILED);
    }

    @Test
    void 병렬_상한은_옵션으로_받고_설정_파일은_읽지_않는다(@TempDir Path repo) throws Exception {
        assertThat(ExperimentMain.maxParallelRuns("--max-parallel-runs=2")).isEqualTo(2);
        assertThat(ExperimentMain.maxParallelRuns("--max-parallel-runs=64")).isEqualTo(64);
        assertThat(ExperimentMain.maxParallelRuns("--max-parallel-runs=0")).isNull();
        assertThat(ExperimentMain.maxParallelRuns("--max-parallel-runs=65")).isNull();
        assertThat(ExperimentMain.maxParallelRuns("--max-parallel-runs=x")).isNull();
        Clock clock = Clock.fixed(WEEKEND, ZoneOffset.UTC);

        assertThat(ExperimentMain.run(new String[] {"combination", "--max-parallel-runs=0", repo.toString(), "abc",
                "false", JOB}, clock, config)).isEqualTo(1);
        assertThat(files().status(JOB)).doesNotExist(); // 옵션 오류는 작업을 시작하지 않는다

        // runtime-config.json에 다른 값이 있어도 이 명령은 읽지 않는다(D-063 설정 로더 금지)
        Files.writeString(config.resolve("runtime-config.json"), "{\"lab\":{\"max-parallel-runs\":1}}");
        ExperimentMain.run(new String[] {"combination", repo.toString(), "abc", "false", JOB}, clock, config);
        assertThat(status().maxParallelRuns()).isEqualTo(ExperimentMain.DEFAULT_MAX_PARALLEL_RUNS);
        ExperimentMain.run(new String[] {"combination", "--max-parallel-runs=2", repo.toString(), "abc", "false", JOB},
                clock, config);
        assertThat(status().maxParallelRuns()).isEqualTo(2);
    }

    @Test
    void 옵션이_없으면_실험실이_쓴_상한_파일을_따르고_옵션이_이긴다(@TempDir Path repo) throws Exception {
        Clock clock = Clock.fixed(WEEKEND, ZoneOffset.UTC);
        new ExperimentLimits(2, WEEKEND).write(config);

        ExperimentMain.run(new String[] {"combination", repo.toString(), "abc", "false", JOB}, clock, config);
        assertThat(status().maxParallelRuns()).isEqualTo(2);
        ExperimentMain.run(new String[] {"combination", "--max-parallel-runs=5", repo.toString(), "abc", "false", JOB},
                clock, config);
        assertThat(status().maxParallelRuns()).isEqualTo(5);

        assertThat(ExperimentMain.maxParallelRuns(null, config).source()).contains("lab.max-parallel-runs");
        assertThat(ExperimentMain.maxParallelRuns(7, config)).isEqualTo(new ExperimentMain.MaxParallel(7, "-PmaxParallelRuns"));
        Files.delete(ExperimentLimits.file(config));
        assertThat(ExperimentMain.maxParallelRuns(null, config).value()).isEqualTo(ExperimentMain.DEFAULT_MAX_PARALLEL_RUNS);
    }

    @Test
    void 상한_파일이_깨졌으면_기본값으로_넘지_않고_입력_오류다(@TempDir Path repo) throws Exception {
        // 실험실에서 1로 낮춘 상한이 파일 손상으로 기본 4가 되지 않게 한다(D-094)
        Files.createDirectories(ExperimentLimits.file(config).getParent());
        Files.writeString(ExperimentLimits.file(config), "format: experiment-limits-v1\nmax-parallel-runs: 0\n");
        Clock clock = Clock.fixed(WEEKEND, ZoneOffset.UTC);
        String[] args = {"combination", repo.toString(), "abc", "false", JOB};
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertThat(ExperimentMain.run(args, clock, config)).isEqualTo(1);
        } finally {
            System.setErr(err);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("병렬 상한 파일", "-PmaxParallelRuns=N");
        assertThat(files().status(JOB)).doesNotExist(); // 작업을 시작하지 않는다

        // 옵션을 주면 파일을 보지 않는다
        ExperimentMain.run(new String[] {"combination", "--max-parallel-runs=1", repo.toString(), "abc", "false", JOB},
                clock, config);
        assertThat(status().maxParallelRuns()).isEqualTo(1);

        // 정지 요청이 있으면 상한 파일 오류보다 종료 코드 4가 먼저다
        Files.writeString(config.resolve(ExperimentGate.STOP_REQUEST), "");
        assertThat(ExperimentMain.run(args, clock, config)).isEqualTo(4);
    }

    @Test
    void 작업_폴더를_쓸_수_없어도_정지_요청이면_종료_코드_4다(@TempDir Path repo) throws IOException {
        // 디스크 고갈처럼 작업 폴더를 못 쓰는 때에도 감시의 정지 요청은 4와 이유로 알린다(장애표 #10, PR #113 risk-ops F1)
        Files.createDirectories(config.resolve("experiment-state"));
        Files.writeString(config.resolve(ExperimentJobFiles.JOBS_DIR), "디렉터리 자리에 파일");
        Clock clock = Clock.fixed(WEEKEND, ZoneOffset.UTC);
        String[] args = {"combination", repo.toString(), "abc", "false", JOB};

        assertThat(ExperimentMain.run(args, clock, config)).isEqualTo(1); // 정지 요청 없음: 입력 오류
        Files.writeString(config.resolve(ExperimentGate.STOP_REQUEST), "");
        assertThat(ExperimentMain.run(args, clock, config)).isEqualTo(4);
        assertThat(ExperimentMain.run(new String[] {"combination", "--allow-during-session", repo.toString(), "abc",
                "false", JOB}, clock, config)).isEqualTo(4);
    }

    @Test
    void 같은_실험이_도는_중이어도_정지_요청이면_종료_코드_4다(@TempDir Path repo) throws Exception {
        ExperimentJob running = start(new MovingClock(WEEKEND));
        Clock clock = Clock.fixed(WEEKEND, ZoneOffset.UTC);
        String[] args = {"combination", repo.toString(), "abc", "false", JOB};

        assertThat(ExperimentMain.run(args, clock, config)).isEqualTo(1);
        Files.writeString(config.resolve(ExperimentGate.STOP_REQUEST), "");
        assertThat(ExperimentMain.run(args, clock, config)).isEqualTo(4);
        running.finish(0);
    }

    @Test
    void 설정_폴더는_상대_경로와_점점이_섞여도_정규화한_절대_경로로_쓴다(@TempDir Path repo) throws Exception {
        // architect 2차 R8·risk-ops 2차 R4: 상태 파일 메시지에서 설정 폴더를 가리려면 경로 표기가 하나여야 한다
        Path absoluteMessy = config.resolve("sub").resolve("..");
        List<Path> cases = new java.util.ArrayList<>(List.of(absoluteMessy));
        // 상대 경로는 작업 폴더와 임시 폴더가 같은 루트일 때만 만들 수 있다. Windows에서 드라이브가 다르면
        // relativize가 IllegalArgumentException을 던진다(PR #113 architect 3차 R9)
        Path cwd = Path.of("").toAbsolutePath();
        if (cwd.getRoot().equals(absoluteMessy.getRoot())) {
            Path relativeMessy = cwd.relativize(absoluteMessy);
            assertThat(relativeMessy.isAbsolute()).isFalse();
            cases.add(relativeMessy);
        }
        Path normalized = config.toAbsolutePath().normalize();
        for (Path messy : cases) {
            PrintStream original = System.out;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            try {
                ExperimentMain.run(new String[] {"combination", repo.toString(), "abc", "false", JOB},
                        Clock.fixed(WEEKEND, ZoneOffset.UTC), messy);
            } finally {
                System.setOut(original);
            }

            String printed = out.toString(StandardCharsets.UTF_8);
            assertThat(printed).as(messy.toString())
                    .contains("진행 상태 파일: " + ExperimentJobFiles.in(normalized).status(JOB))
                    .contains("정지 요청 파일 확인 위치: " + normalized.resolve(ExperimentGate.STOP_REQUEST))
                    .doesNotContain("sub" + File.separator + "..");
        }
    }

    @Test
    void 작업_ID는_실험_ID_인자에서_정한다() {
        assertThat(ExperimentMain.jobId("study", List.of("r", "c", "false"))).isEqualTo("EXP-20261008-STR01");
        assertThat(ExperimentMain.jobId("combination", List.of("r", "c", "false", JOB, "--verify-determinism")))
                .isEqualTo(JOB);
        assertThat(ExperimentMain.jobId("combination", List.of("r", "c", "false"))).isNull();
        assertThat(ExperimentMain.jobId("combination", List.of("r", "c", "false", "../x"))).isNull();
    }

    @Test
    void 실행_명령은_입력_오류도_상태_파일에_남기고_잠금을_푼다(@TempDir Path repo) throws Exception {
        Clock clock = Clock.fixed(WEEKEND, ZoneOffset.UTC);
        String[] args = {"combination", repo.toString(), "abc", "false", JOB};

        assertThat(ExperimentMain.run(args, clock, config)).isEqualTo(1); // 등록 파일 없음
        assertThat(status().state()).isEqualTo(ExperimentJobStatus.State.INPUT_ERROR);
        assertThat(status().command()).isEqualTo("combination");
        start(clock).finish(0);
    }
}
