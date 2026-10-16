package com.autotrade.sim.run;

import com.autotrade.sim.ExperimentId;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * 실험 실행 명령 하나(작업)의 상태 파일 내용(SIM-14·UI-02 진행 보고, D-089). 실행 명령({@code autotrade-app-experiment})이
 * {@code <설정 폴더>/experiment-state/jobs/<실험ID>.status}에 쓰고 실험실이 읽기만 한다. 두 프로세스가 같은 코덱을 쓰도록
 * 여기 둔다. 형식은 UTF-8 {@code 키: 값} 줄이고 첫 줄이 {@value #FORMAT}이다. 모르는 키, 빠진 키, 범위 밖 값은 읽기 거부.
 * 이전 형식 {@value #FORMAT_V1}(명령 전체 실험 수 {@code experiments-planned} 없음)도 읽는다(D-098). 그때 전체 실험 수는 0(모름).
 *
 * @param job 작업 ID = 실험 ID(결과 폴더 이름)
 * @param command 실행 명령 종류(combination, recording, study, walkforward, live-check, live-weekly)
 * @param state 작업 상태
 * @param pid 실행 명령 프로세스 ID(표시용. 실험실은 이 값으로 프로세스에 아무것도 하지 않는다)
 * @param startedAt 시작 시각
 * @param updatedAt 마지막으로 쓴 시각. 실행 중에는 {@link #HEARTBEAT}마다 갱신한다
 * @param maxParallelRuns 이 명령이 쓴 병렬 Run 상한(-PmaxParallelRuns)
 * @param experimentsStarted 이 명령이 시작한 실험(실행기 호출) 수. 결정론 확인·워크포워드는 여러 번 부른다
 * @param experimentsPlanned 이 명령이 부를 실험 수를 미리 알린 값({@link RunGate#onExperimentsPlanned}, D-098). 모르면 0
 * @param progress 지금 실험의 마지막 진행 상태. 아직 없으면 null
 * @param message 끝난 이유나 알림 한 줄(줄바꿈 없음, {@value #MAX_MESSAGE}자 이하). 없으면 빈 문자열
 */
public record ExperimentJobStatus(
        String job,
        String command,
        State state,
        long pid,
        Instant startedAt,
        Instant updatedAt,
        int maxParallelRuns,
        int experimentsStarted,
        int experimentsPlanned,
        ExperimentProgress progress,
        String message) {

    /** 첫 줄. 형식을 바꾸면 숫자를 올린다. */
    public static final String FORMAT = "experiment-job-v2";
    /** 이전 형식(D-089). {@code experiments-planned} 키가 없다. 읽기만 한다. */
    public static final String FORMAT_V1 = "experiment-job-v1";
    /** 실행 중 상태 파일 갱신 주기. */
    public static final Duration HEARTBEAT = Duration.ofSeconds(5);
    /** 실행 중인데 이보다 오래 갱신이 없으면 응답 없음(프로세스가 강제 종료됐거나 멈춤)으로 본다. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(60);
    /** 상태 파일 크기 상한(읽는 쪽 메모리 보호). */
    public static final int MAX_BYTES = 16 * 1024;
    public static final int MAX_MESSAGE = 500;

    /** 실행 명령 종류. */
    public static final List<String> COMMANDS =
            List.of("combination", "recording", "study", "walkforward", "live-check", "live-weekly");

    /** 작업 상태. 끝난 상태는 실행 명령의 종료 코드에 대응한다. */
    public enum State {
        /** 실행 중. */
        RUNNING,
        /** 종료 코드 0: 완료. */
        COMPLETED,
        /** 종료 코드 2: 결과는 썼지만 실패한 Run이나 결정론 불일치가 있어 확인 필요. */
        NEEDS_REVIEW,
        /** 종료 코드 1: 입력 오류로 실행하지 않음. */
        INPUT_ERROR,
        /** 종료 코드 3: 사람이 취소(결과 파일 없음). */
        CANCELLED,
        /** 종료 코드 4: 장 시간 보호·정지 요청 파일로 멈춤(결과 파일 없음). */
        STOPPED,
        /** 그 밖의 종료 코드나 예상 못 한 오류. */
        FAILED;

        /** 실행 명령 종료 코드를 끝난 상태로. */
        public static State ofExitCode(int code) {
            return switch (code) {
                case 0 -> COMPLETED;
                case 1 -> INPUT_ERROR;
                case 2 -> NEEDS_REVIEW;
                case 3 -> CANCELLED;
                case 4 -> STOPPED;
                default -> FAILED;
            };
        }
    }

    public ExperimentJobStatus {
        new ExperimentId(Objects.requireNonNull(job, "job")); // 파일 이름에 쓰므로 실험 ID 형식만
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("모르는 실행 명령입니다: " + command);
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (pid < 0) {
            throw new IllegalArgumentException("pid는 0 이상이어야 합니다: " + pid);
        }
        if (maxParallelRuns < 1 || maxParallelRuns > 64) {
            throw new IllegalArgumentException("maxParallelRuns는 1~64여야 합니다: " + maxParallelRuns);
        }
        if (experimentsStarted < 0) {
            throw new IllegalArgumentException("experimentsStarted는 0 이상이어야 합니다: " + experimentsStarted);
        }
        if (experimentsPlanned < 0) {
            throw new IllegalArgumentException("experimentsPlanned는 0 이상이어야 합니다: " + experimentsPlanned);
        }
        message = oneLine(message == null ? "" : message);
    }

    /** 줄바꿈·제어 문자를 공백으로 바꾸고 길이를 자른다. */
    public static String oneLine(String text) {
        StringBuilder b = new StringBuilder(Math.min(text.length(), MAX_MESSAGE));
        for (int i = 0; i < text.length() && b.length() < MAX_MESSAGE; i++) {
            char c = text.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString().strip();
    }

    /** 실행 중인데 {@link #STALE_AFTER}보다 오래 갱신이 없음. */
    public boolean stale(Instant now) {
        return state == State.RUNNING && updatedAt.plus(STALE_AFTER).isBefore(now);
    }

    /**
     * 명령 전체 진행률 천분율(0~1000, D-098). 전체 실험 수를 모르면(0) 비어 있다. 실험마다 같은 무게로 센다: 끝난 실험 수에 지금
     * 실험의 진행률({@link ExperimentProgress#permille})을 더해 전체 실험 수로 나눈다. 시작한 실험 수가 알린 수를 넘으면
     * 1000에서 멈춘다. 표시용이라 정수로 내림한다.
     */
    public OptionalInt commandPermille() {
        if (experimentsPlanned == 0) {
            return OptionalInt.empty();
        }
        long done = 0;
        if (progress != null && experimentsStarted > 0) {
            boolean currentDone = progress.phase() == ExperimentProgress.Phase.FINISHED;
            done = (experimentsStarted - (currentDone ? 0 : 1)) * 1000L + (currentDone ? 0 : progress.permille());
        }
        return OptionalInt.of((int) Math.min(1000, done / experimentsPlanned));
    }

    public ExperimentJobStatus withState(State newState, Instant at, String newMessage) {
        return new ExperimentJobStatus(job, command, newState, pid, startedAt, at, maxParallelRuns, experimentsStarted,
                experimentsPlanned, progress, newMessage);
    }

    public ExperimentJobStatus withProgress(ExperimentProgress p, Instant at) {
        int started = experimentsStarted + (p.phase() == ExperimentProgress.Phase.STARTED ? 1 : 0);
        return new ExperimentJobStatus(job, command, state, pid, startedAt, at, maxParallelRuns, started,
                experimentsPlanned, p, message);
    }

    /** 명령이 부를 전체 실험 수를 알림(D-098). */
    public ExperimentJobStatus withExperimentsPlanned(int planned, Instant at) {
        return new ExperimentJobStatus(job, command, state, pid, startedAt, at, maxParallelRuns, experimentsStarted,
                planned, progress, message);
    }

    public ExperimentJobStatus touched(Instant at) {
        return new ExperimentJobStatus(job, command, state, pid, startedAt, at, maxParallelRuns, experimentsStarted,
                experimentsPlanned, progress, message);
    }

    /** 상태 파일 내용. */
    public String encode() {
        StringBuilder b = new StringBuilder();
        b.append(FORMAT).append('\n');
        line(b, "job", job);
        line(b, "command", command);
        line(b, "state", state.name());
        line(b, "pid", Long.toString(pid));
        line(b, "started-at", startedAt.toString());
        line(b, "updated-at", updatedAt.toString());
        line(b, "max-parallel-runs", Integer.toString(maxParallelRuns));
        line(b, "experiments-started", Integer.toString(experimentsStarted));
        line(b, "experiments-planned", Integer.toString(experimentsPlanned));
        if (progress != null) {
            line(b, "experiment", progress.experimentId());
            line(b, "phase", progress.phase().name());
            line(b, "runs-total", Integer.toString(progress.runsTotal()));
            line(b, "runs-started", Integer.toString(progress.runsStarted()));
            line(b, "runs-finished", Integer.toString(progress.runsFinished()));
            line(b, "runs-failed", Integer.toString(progress.runsFailed()));
            line(b, "days-per-run", Integer.toString(progress.daysPerRun()));
            line(b, "days-done", Long.toString(progress.daysDone()));
        }
        line(b, "message", message);
        return b.toString();
    }

    private static void line(StringBuilder b, String key, String value) {
        b.append(key).append(": ").append(value).append('\n');
    }

    private static final List<String> BASE_KEYS = List.of("job", "command", "state", "pid", "started-at", "updated-at",
            "max-parallel-runs", "experiments-started", "message");
    /** v2에서 더한 키(v1에는 없어야 한다). */
    private static final List<String> V2_KEYS = List.of("experiments-planned");
    private static final List<String> PROGRESS_KEYS = List.of("experiment", "phase", "runs-total", "runs-started",
            "runs-finished", "runs-failed", "days-per-run", "days-done");

    /** 상태 파일 읽기. 형식이 맞지 않으면 {@link IllegalArgumentException}(메시지에 파일 내용을 싣지 않는다). */
    public static ExperimentJobStatus decode(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_BYTES) {
            throw new IllegalArgumentException("상태 파일이 너무 큽니다");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !(lines[0].equals(FORMAT) || lines[0].equals(FORMAT_V1))
                || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("상태 파일 형식이 아닙니다(" + FORMAT + ", " + FORMAT_V1 + ")");
        }
        boolean v1 = lines[0].equals(FORMAT_V1);
        List<String> required = v1 ? BASE_KEYS
                : Stream.concat(BASE_KEYS.stream(), V2_KEYS.stream()).toList();
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String l = lines[i];
            int colon = l.indexOf(": ");
            String key = colon < 0 ? (l.endsWith(":") ? l.substring(0, l.length() - 1) : l) : l.substring(0, colon);
            String value = colon < 0 ? "" : l.substring(colon + 2);
            if (!required.contains(key) && !PROGRESS_KEYS.contains(key)) {
                throw new IllegalArgumentException("상태 파일에 모르는 키가 있습니다: " + oneLine(key));
            }
            if (values.put(key, value) != null) {
                throw new IllegalArgumentException("상태 파일에 같은 키가 두 번 있습니다: " + key);
            }
        }
        for (String k : required) {
            if (!values.containsKey(k)) {
                throw new IllegalArgumentException("상태 파일에 키가 없습니다: " + k);
            }
        }
        long present = PROGRESS_KEYS.stream().filter(values::containsKey).count();
        if (present != 0 && present != PROGRESS_KEYS.size()) {
            throw new IllegalArgumentException("상태 파일의 진행 키가 일부만 있습니다");
        }
        try {
            ExperimentProgress progress = present == 0 ? null : new ExperimentProgress(values.get("experiment"),
                    ExperimentProgress.Phase.valueOf(values.get("phase")), Integer.parseInt(values.get("runs-total")),
                    Integer.parseInt(values.get("runs-started")), Integer.parseInt(values.get("runs-finished")),
                    Integer.parseInt(values.get("runs-failed")), Integer.parseInt(values.get("days-per-run")),
                    Long.parseLong(values.get("days-done")));
            if (progress != null) {
                new ExperimentId(progress.experimentId());
            }
            return new ExperimentJobStatus(values.get("job"), values.get("command"), State.valueOf(values.get("state")),
                    Long.parseLong(values.get("pid")), Instant.parse(values.get("started-at")),
                    Instant.parse(values.get("updated-at")), Integer.parseInt(values.get("max-parallel-runs")),
                    Integer.parseInt(values.get("experiments-started")),
                    v1 ? 0 : Integer.parseInt(values.get("experiments-planned")), progress, values.get("message"));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("상태 파일의 숫자·시각 형식이 아닙니다", e);
        }
    }
}
