package com.autotrade.app.experiment;

import com.autotrade.sim.run.ExperimentCancelledException;
import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentJobStatus;
import com.autotrade.sim.run.ExperimentProgress;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 실험 실행 명령 하나의 작업 제어(SIM-14 진행 보고·취소·자원 상한, D-089). 실행기에 {@link RunGate}로 들어간다.
 *
 * <ul>
 *   <li><b>정지 확인</b>: 먼저 {@link ExperimentGate}(감시의 정지 요청 파일, 장 시간 보호, D-063)를 묻고, 그다음 이 실험의 취소
 *       요청 파일({@link ExperimentJobFiles#cancelRequest})을 본다. 취소면 {@link ExperimentCancelledException}(종료 코드 3).</li>
 *   <li><b>진행 보고</b>: 실행기가 넘긴 진행 상태와 명령이 미리 알린 전체 실험 수(D-098)를 상태 파일에 쓴다. 단계가 바뀔 때는 바로, 진행 중에는 1초에 한 번까지만 쓰고,
 *       따로 {@link ExperimentJobStatus#HEARTBEAT}마다 갱신 시각만 다시 써 실험실이 멈춘 명령을 알아보게 한다. 파일 쓰기
 *       실패는 실험을 멈추지 않는다(경고 한 번).</li>
 *   <li><b>병렬 상한</b>: 실행 명령 옵션 {@code --max-parallel-runs}(Gradle {@code -PmaxParallelRuns}) 값을 실행기에 준다.</li>
 *   <li><b>단일 실행</b>: 같은 실험 ID를 두 명령이 함께 돌리지 못하게 잠금 파일을 OS 잠금으로 잡는다(결과 폴더 덮어쓰기 방지).</li>
 * </ul>
 *
 * <p>결과에는 영향이 없다: 진행 보고는 실행기에 돌려주는 값이 없고, 병렬 수는 결과를 바꾸지 않으며, 취소면 결과가 없다.
 */
final class ExperimentJob implements RunGate, AutoCloseable {

    /** 진행 중 상태 파일을 쓰는 최소 간격. */
    static final Duration WRITE_INTERVAL = Duration.ofSeconds(1);

    private final ExperimentGate gate;
    private final ExperimentJobFiles files;
    private final Clock clock;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ScheduledExecutorService heartbeat;
    private final Path cancelRequest;
    private final Path statusFile;
    private ExperimentJobStatus status;
    private Instant lastWrite = Instant.MIN;
    private volatile String lastStopReason = "";
    private boolean warned;
    private boolean finished;

    /** 같은 실험이 이미 다른 명령에서 도는 중. */
    static final class AlreadyRunningException extends Exception {

        private static final long serialVersionUID = 1L;

        AlreadyRunningException(String message) {
            super(message);
        }
    }

    private ExperimentJob(ExperimentGate gate, ExperimentJobFiles files, Clock clock, FileChannel lockChannel,
            FileLock lock, ExperimentJobStatus status, Duration heartbeatEvery) {
        this.gate = gate;
        this.files = files;
        this.clock = clock;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.status = status;
        this.cancelRequest = files.cancelRequest(status.job());
        this.statusFile = files.status(status.job());
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "experiment-job-heartbeat");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long ms = heartbeatEvery.toMillis();
        heartbeat.scheduleWithFixedDelay(this::beat, ms, ms, TimeUnit.MILLISECONDS);
    }

    /**
     * 잠금을 잡고, 남은 취소 요청을 지우고, 실행 중 상태를 쓴다.
     *
     * @throws AlreadyRunningException 같은 실험 ID의 잠금을 다른 프로세스가 쥐고 있음
     * @throws IOException 작업 폴더를 만들거나 잠금 파일을 열 수 없음
     */
    static ExperimentJob start(String job, String command, ExperimentGate gate, int maxParallelRuns, Clock clock,
            ExperimentJobFiles files, long pid) throws AlreadyRunningException, IOException {
        return start(job, command, gate, maxParallelRuns, clock, files, pid, ExperimentJobStatus.HEARTBEAT);
    }

    static ExperimentJob start(String job, String command, ExperimentGate gate, int maxParallelRuns, Clock clock,
            ExperimentJobFiles files, long pid, Duration heartbeatEvery) throws AlreadyRunningException, IOException {
        Objects.requireNonNull(gate, "gate");
        Files.createDirectories(files.dir());
        FileChannel channel = FileChannel.open(files.lock(job), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // 같은 JVM 안에서 이미 잡음
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new AlreadyRunningException("같은 실험이 이미 실행 중입니다: " + job + " (" + files.lock(job) + ")");
        }
        try {
            // 지난 실행에서 남은 취소 요청이 새 실행을 멈추지 않게 지운다(이 실험의 잠금을 쥔 뒤라 다른 실행의 요청이 아님)
            Files.deleteIfExists(files.cancelRequest(job));
            Instant now = clock.instant();
            ExperimentJobStatus first = new ExperimentJobStatus(job, command, ExperimentJobStatus.State.RUNNING, pid, now,
                    now, maxParallelRuns, 0, 0, null, "");
            files.write(first);
            return new ExperimentJob(gate, files, clock, channel, lock, first, heartbeatEvery);
        } catch (IOException | RuntimeException e) {
            lock.release();
            channel.close();
            throw e;
        }
    }

    @Override
    public Optional<String> stopReason() {
        Optional<String> stop = gate.stopReason();
        if (stop.isPresent()) {
            lastStopReason = stop.get();
            return stop;
        }
        if (cancelRequested()) {
            lastStopReason = cancelMessage();
            return Optional.of(lastStopReason);
        }
        return Optional.empty();
    }

    /** 자원 보호 정지면 {@link ExperimentStoppedException}(종료 코드 4), 취소면 {@link ExperimentCancelledException}(3). */
    @Override
    public void check() {
        Optional<String> stop = gate.stopReason();
        if (stop.isPresent()) {
            lastStopReason = stop.get();
            throw new ExperimentStoppedException(lastStopReason);
        }
        if (cancelRequested()) {
            lastStopReason = cancelMessage();
            throw new ExperimentCancelledException(lastStopReason);
        }
    }

    private boolean cancelRequested() {
        // 정지 요청과 달리 확인 실패를 취소로 보지 않는다: 취소는 사람의 선택이고 자원 보호는 정지 요청 파일이 맡는다
        return Files.exists(cancelRequest);
    }

    private String cancelMessage() {
        return "실험실에서 취소를 요청했습니다(" + cancelRequest + ")";
    }

    @Override
    public synchronized void onProgress(ExperimentProgress progress) {
        if (finished) {
            return;
        }
        Instant now = clock.instant();
        status = status.withProgress(progress, now);
        if (progress.phase() != ExperimentProgress.Phase.RUNNING
                || !now.isBefore(lastWrite.plus(WRITE_INTERVAL))) {
            write();
        }
    }

    /** 명령 전체 실험 수(D-098)는 바로 쓴다. 한 명령에 한두 번이다. */
    @Override
    public synchronized void onExperimentsPlanned(int count) {
        if (finished) {
            return;
        }
        status = status.withExperimentsPlanned(count, clock.instant());
        write();
    }

    @Override
    public int maxParallelRuns() {
        return status.maxParallelRuns();
    }

    /** 지금 상태(시험·출력용). */
    synchronized ExperimentJobStatus status() {
        return status;
    }

    private synchronized void beat() {
        if (finished) {
            return;
        }
        status = status.touched(clock.instant());
        write();
    }

    private void write() {
        try {
            files.write(status);
            lastWrite = status.updatedAt();
        } catch (IOException | RuntimeException e) {
            if (!warned) {
                warned = true;
                System.err.println("[experiment] 상태 파일을 쓰지 못했습니다(실험은 계속): " + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 취소(종료 코드 3)로 끝났는데 그사이 감시의 정지 요청이나 장 시간 보호가 생겼으면 정지(종료 코드 4)로 올린다. 실행기는 처음 받은
     * 정지 예외 하나만 남기므로 이렇게 다시 보지 않으면 그 회차에는 장애표 #10의 감지 단서(종료 코드 4와 정지 이유)가 어디에도 남지
     * 않는다(독립 QA PR #113 R1).
     *
     * @return 올렸으면 4, 아니면 받은 종료 코드
     */
    int afterCancel(int exitCode) {
        if (exitCode != 3) {
            return exitCode;
        }
        Optional<String> stop = gate.stopReason();
        if (stop.isEmpty()) {
            return exitCode;
        }
        lastStopReason = stop.get();
        System.err.println("[experiment] 취소 요청으로 멈추는 사이 정지 사유가 생겼습니다(결과 파일을 쓰지 않음, 종료 코드 4): "
                + stop.get());
        return 4;
    }

    /**
     * 끝난 상태를 쓰고 취소 요청을 지우고 잠금을 푼다. 한 번만 동작한다.
     *
     * @param exitCode 실행 명령 종료 코드({@link ExperimentJobStatus.State#ofExitCode})
     */
    void finish(int exitCode) {
        heartbeat.shutdownNow();
        synchronized (this) {
            if (finished) {
                return;
            }
            ExperimentJobStatus.State state = ExperimentJobStatus.State.ofExitCode(exitCode);
            String message = switch (state) {
                case CANCELLED, STOPPED -> lastStopReason;
                case COMPLETED -> "";
                default -> "종료 코드 " + exitCode;
            };
            status = status.withState(state, clock.instant(), withoutConfigDir(message));
            write();
            finished = true;
        }
        try {
            Files.deleteIfExists(cancelRequest);
        } catch (IOException e) {
            System.err.println("[experiment] 취소 요청 파일을 지우지 못했습니다(다음 실행 시작 때 지움): "
                    + e.getClass().getSimpleName());
        }
        try {
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            // 프로세스가 끝나면 OS가 푼다
        }
    }

    /**
     * 상태 파일(실험실 REST로 나감)에는 설정 폴더 절대 경로를 싣지 않는다(ADR-005 live {@code stopReason}과 같은 기준, PR #113
     * architect R2·risk-ops R1). 콘솔 출력에는 그대로 둔다.
     */
    private String withoutConfigDir(String message) {
        Path configDir = files.dir().getParent().getParent();
        return message.replace(configDir.toString(), "<설정 폴더>");
    }

    /** {@link #finish}를 부르지 않고 닫히면 예상 못 한 오류로 끝난 것이다. */
    @Override
    public void close() {
        finish(-1);
    }

    Path statusFile() {
        return statusFile;
    }
}
