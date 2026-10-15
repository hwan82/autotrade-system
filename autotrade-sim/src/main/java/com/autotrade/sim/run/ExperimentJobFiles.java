package com.autotrade.sim.run;

import com.autotrade.sim.ExperimentId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * 실험 실행 명령의 작업 파일 위치와 읽기·쓰기(D-089). 실행 명령과 실험실이 같은 위치를 쓰도록 여기서만 정한다.
 *
 * <ul>
 *   <li>{@code <설정 폴더>/experiment-state/jobs/<실험ID>.status}: 상태 파일. 실행 명령만 쓴다(임시 파일에 쓰고 바꿔 넣음).</li>
 *   <li>{@code <실험ID>.lock}: 같은 실험을 두 명령이 함께 돌리지 못하게 실행 명령이 잡는 OS 파일 잠금. 프로세스가 죽으면 OS가 푼다.</li>
 *   <li>{@code <실험ID>.cancel-request}: 취소 요청 파일. 실험실이 만들고(내용 없음), 실행 명령이 시작·끝에 지운다. 실행 명령은
 *       Run 시작 전과 거래일마다 확인해 있으면 결과 없이 끝난다(종료 코드 3).</li>
 * </ul>
 *
 * <p>감시의 정지 요청 파일({@code experiment-state/stop-request}, D-063)과는 다른 파일이다. 취소 요청은 실험 하나만 멈추고,
 * 실험실이 만들 수 있고, 실행 명령이 지울 수 있다.
 */
public record ExperimentJobFiles(Path dir) {

    /** 설정 폴더 안 위치. */
    public static final String JOBS_DIR = "experiment-state/jobs";

    public ExperimentJobFiles {
        dir = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
    }

    public static ExperimentJobFiles in(Path configDir) {
        return new ExperimentJobFiles(configDir.resolve(JOBS_DIR));
    }

    public Path status(String job) {
        return dir.resolve(new ExperimentId(job).value() + ".status");
    }

    public Path lock(String job) {
        return dir.resolve(new ExperimentId(job).value() + ".lock");
    }

    public Path cancelRequest(String job) {
        return dir.resolve(new ExperimentId(job).value() + ".cancel-request");
    }

    /** 상태 파일을 바꿔 넣는다(읽는 쪽이 반쯤 쓴 파일을 보지 않게). */
    public void write(ExperimentJobStatus status) throws IOException {
        Files.createDirectories(dir);
        Path target = status(status.job());
        Path tmp = dir.resolve(status.job() + ".status.tmp");
        Files.writeString(tmp, status.encode(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 상태 파일 읽기. {@link ExperimentJobStatus#MAX_BYTES}를 넘으면 앞부분만 읽지 않고 거부한다. */
    public ExperimentJobStatus read(Path statusFile) throws IOException {
        try (InputStream in = Files.newInputStream(statusFile)) {
            byte[] bytes = in.readNBytes(ExperimentJobStatus.MAX_BYTES + 1);
            if (bytes.length > ExperimentJobStatus.MAX_BYTES) {
                throw new IllegalArgumentException("상태 파일이 너무 큽니다");
            }
            return ExperimentJobStatus.decode(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
