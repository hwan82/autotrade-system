package com.autotrade.sim.run;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 실험실 설정 화면의 실험 자원 상한을 실험 실행 명령에 넘기는 파일(D-094, D-089 결정 4 변경 제안 ①).
 *
 * <p>위치는 {@code <설정 폴더>/experiment-state/limits}. 실험실이 기동할 때와 설정 화면에서 저장할 때 {@code lab.max-parallel-runs}
 * 값으로 쓰고, 실험 실행 명령은 {@code --max-parallel-runs}(Gradle {@code -PmaxParallelRuns})가 없을 때만 읽는다. 실행 명령은
 * 설정 로더와 {@code runtime-config.json}·{@code secrets.json}을 모르므로(보호 규칙 {@code EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG},
 * D-063) 비밀값이 없는 이 파일만 본다. 형식은 상태 파일과 같은 {@code 키: 값} 줄이고, 임시 파일에 쓰고 바꿔 넣는다.
 *
 * @param maxParallelRuns 동시에 도는 Run 수 상한(1~64)
 * @param updatedAt 실험실이 쓴 시각(표시용)
 */
public record ExperimentLimits(int maxParallelRuns, Instant updatedAt) {

    /** 첫 줄. */
    public static final String FORMAT = "format: experiment-limits-v1";
    /** 설정 폴더 안 위치. */
    public static final String FILE = "experiment-state/limits";
    /** 파일 크기 상한. 넘으면 앞부분만 읽지 않고 거부한다. */
    public static final int MAX_BYTES = 1024;

    private static final List<String> KEYS = List.of("max-parallel-runs", "updated-at");

    public ExperimentLimits {
        if (maxParallelRuns < 1 || maxParallelRuns > 64) {
            throw new IllegalArgumentException("max-parallel-runs는 1~64여야 합니다: " + maxParallelRuns);
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 설정 폴더 안 파일 경로. */
    public static Path file(Path configDir) {
        return configDir.resolve(FILE).toAbsolutePath().normalize();
    }

    public String encode() {
        return FORMAT + "\n" + "max-parallel-runs: " + maxParallelRuns + "\n" + "updated-at: " + updatedAt + "\n";
    }

    /** 형식이 맞지 않으면 {@link IllegalArgumentException}(메시지에 파일 내용을 싣지 않는다). */
    public static ExperimentLimits decode(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_BYTES) {
            throw new IllegalArgumentException("상한 파일이 너무 큽니다");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].equals(FORMAT) || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("상한 파일 형식이 아닙니다(" + FORMAT + ")");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < lines.length - 1; i++) {
            String l = lines[i];
            int colon = l.indexOf(": ");
            String key = colon < 0 ? "" : l.substring(0, colon);
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("상한 파일에 모르는 줄이 있습니다(" + i + "번째)");
            }
            if (values.put(key, l.substring(colon + 2)) != null) {
                throw new IllegalArgumentException("상한 파일에 같은 키가 두 번 있습니다: " + key);
            }
        }
        for (String k : KEYS) {
            if (!values.containsKey(k)) {
                throw new IllegalArgumentException("상한 파일에 키가 없습니다: " + k);
            }
        }
        try {
            return new ExperimentLimits(Integer.parseInt(values.get("max-parallel-runs")),
                    Instant.parse(values.get("updated-at")));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException("상한 파일의 숫자·시각 형식이 아닙니다", e);
        }
    }

    /** 바꿔 넣어 쓴다(읽는 쪽이 반쯤 쓴 파일을 보지 않게). */
    public void write(Path configDir) throws IOException {
        Path target = file(configDir);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("limits.tmp");
        Files.writeString(tmp, encode(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 읽기. 파일이 없으면 빈 값. 일반 파일이 아니거나(링크·폴더) 형식이 틀리면 {@link IllegalArgumentException}, 읽기 실패는
     * {@link IOException}.
     */
    public static Optional<ExperimentLimits> read(Path configDir) throws IOException {
        Path f = file(configDir);
        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("상한 파일이 일반 파일이 아닙니다");
        }
        try (InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("상한 파일이 너무 큽니다");
            }
            return Optional.of(decode(new String(bytes, StandardCharsets.UTF_8)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }
}
