package com.autotrade.app.lab.experiment;

import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentJobStatus;
import com.autotrade.sim.run.ExperimentProgress;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 실험 실행 명령의 진행 상태 조회와 취소 요청(SIM-14·UI-02, D-089). 실험실은 실험을 돌리지 않는다(D-062): 실행 명령
 * ({@code autotrade-app-experiment})이 쓰는 상태 파일을 읽기만 하고, 취소는 취소 요청 파일을 만드는 것뿐이다. 프로세스를
 * 종료하거나 신호를 보내지 않는다(PID는 표시용).
 *
 * <p>읽기 상한: 작업 폴더의 상태 파일 {@value #MAX_FILES}개, 파일마다 {@link ExperimentJobStatus#MAX_BYTES}. 깨진 파일은 목록에서
 * 빼고 개수만 알린다.
 */
public final class ExperimentJobs {

    /** 목록에 읽는 상태 파일 수 상한. */
    static final int MAX_FILES = 500;

    private final ExperimentJobFiles files;
    private final Clock clock;

    public ExperimentJobs(Path configDir, Clock clock) {
        this.files = ExperimentJobFiles.in(Objects.requireNonNull(configDir, "configDir"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path root() {
        return files.dir();
    }

    /** 화면에 주는 작업 하나. {@code state}는 실행 중인데 응답이 없으면 {@code UNRESPONSIVE}. */
    public record Job(
            String job,
            String command,
            String state,
            boolean stale,
            long pid,
            Instant startedAt,
            Instant updatedAt,
            int maxParallelRuns,
            int experimentsStarted,
            String experiment,
            String phase,
            Integer runsTotal,
            Integer runsStarted,
            Integer runsFinished,
            Integer runsFailed,
            Integer daysPerRun,
            Long daysDone,
            Long daysTotal,
            Integer permille,
            String message,
            boolean cancelRequested) {
    }

    /** 목록 결과. {@code unreadable}은 읽지 못한(깨졌거나 너무 큰) 상태 파일 수. */
    public record Listing(List<Job> items, int unreadable, boolean truncated) {
    }

    /**
     * 갱신 시각 내림차순(같으면 작업 ID 오름차순). 파일이 {@value #MAX_FILES}개를 넘으면 파일 수정 시각이 최근인 것부터 읽어
     * 오래된 작업이 잘린다(디렉터리 순서로 자르면 최신 작업이 빠질 수 있음, PR #113 architect R3).
     */
    public Listing list() {
        if (!Files.isDirectory(files.dir())) {
            return new Listing(List.of(), 0, false);
        }
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(files.dir(), "*.status")) {
            stream.forEach(paths::add);
        } catch (IOException e) {
            throw new UncheckedIOException("작업 폴더를 읽지 못했습니다: " + files.dir(), e);
        }
        paths.sort(Comparator.comparing(ExperimentJobs::modified).reversed().thenComparing(Path::toString));
        boolean truncated = paths.size() > MAX_FILES;
        List<Job> out = new ArrayList<>();
        int unreadable = 0;
        for (Path p : truncated ? paths.subList(0, MAX_FILES) : paths) {
            try {
                out.add(view(files.read(p)));
            } catch (IOException | RuntimeException e) {
                unreadable++; // 쓰는 중 바꿔 넣기와 겹친 경우도 여기(다음 조회에서 다시 읽음)
            }
        }
        out.sort(Comparator.comparing(Job::updatedAt).reversed().thenComparing(Job::job));
        return new Listing(List.copyOf(out), unreadable, truncated);
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.MIN;
        }
    }

    /**
     * @throws IllegalArgumentException 실험 ID 형식이 아님
     * @throws NoSuchElementException 상태 파일이 없음
     */
    public Job get(String id) {
        return view(read(id));
    }

    /**
     * 취소 요청 파일을 만든다. 실행 명령이 Run 시작 전이나 다음 거래일 마감에 보고 결과 없이 끝난다(종료 코드 3). 이미 요청돼
     * 있으면 그대로 둔다.
     *
     * @throws IllegalStateException 실행 중이 아니거나 응답이 없음(끝났거나 강제 종료된 명령은 취소할 것이 없다)
     */
    public Job cancel(String id) {
        ExperimentJobStatus s = read(id);
        if (s.state() != ExperimentJobStatus.State.RUNNING) {
            throw new IllegalStateException("실행 중인 실험이 아닙니다: " + s.job() + " (" + s.state() + ")");
        }
        if (s.stale(clock.instant())) {
            throw new IllegalStateException("실행 명령이 " + ExperimentJobStatus.STALE_AFTER.toSeconds()
                    + "초 넘게 응답하지 않습니다. 강제 종료됐을 수 있어 취소 요청을 만들지 않습니다: " + s.job());
        }
        try {
            Files.createFile(files.cancelRequest(s.job()));
        } catch (FileAlreadyExistsException e) {
            // 이미 요청됨
        } catch (IOException e) {
            throw new UncheckedIOException("취소 요청 파일을 만들지 못했습니다: " + files.cancelRequest(s.job()), e);
        }
        return view(s);
    }

    private ExperimentJobStatus read(String id) {
        String job = new ExperimentId(id).value();
        try {
            return files.read(files.status(job));
        } catch (NoSuchFileException e) {
            throw new NoSuchElementException("진행 상태가 없습니다: " + job);
        } catch (IOException e) {
            throw new UncheckedIOException("진행 상태를 읽지 못했습니다: " + job, e);
        }
    }

    private Job view(ExperimentJobStatus s) {
        boolean stale = s.stale(clock.instant());
        ExperimentProgress p = s.progress();
        return new Job(s.job(), s.command(), stale ? "UNRESPONSIVE" : s.state().name(), stale, s.pid(), s.startedAt(),
                s.updatedAt(), s.maxParallelRuns(), s.experimentsStarted(),
                p == null ? null : p.experimentId(), p == null ? null : p.phase().name(),
                p == null ? null : p.runsTotal(), p == null ? null : p.runsStarted(),
                p == null ? null : p.runsFinished(), p == null ? null : p.runsFailed(),
                p == null ? null : p.daysPerRun(), p == null ? null : p.daysDone(), p == null ? null : p.daysTotal(),
                p == null ? null : p.permille(), s.message(), Files.exists(files.cancelRequest(s.job())));
    }
}
