package com.autotrade.app.lab.settings;

import com.autotrade.config.RuntimeConfig;
import com.autotrade.sim.run.ExperimentLimits;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 실험실 설정 {@code lab.max-parallel-runs}를 실험 실행 명령이 읽는 상한 파일({@link ExperimentLimits},
 * {@code <설정 폴더>/experiment-state/limits})로 내보낸다(D-094). 실험실 기동 때와 설정 화면 저장 뒤에 쓴다.
 *
 * <p>실행 명령은 설정 로더를 쓰지 못하므로(보호 규칙 {@code EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG}, D-063) 비밀값이 없는 이
 * 파일만 본다. 쓰기에 실패해도 설정 저장은 되돌리지 않는다: 실행 명령은 지난 파일 값(없으면 기본 4)으로 돌고, 실패는 경고 로그와
 * 반환값으로 알린다.
 */
public final class ExperimentLimitsPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExperimentLimitsPublisher.class);

    private final Path configDir;
    private final Clock clock;

    public ExperimentLimitsPublisher(Path configDir, Clock clock) {
        this.configDir = Objects.requireNonNull(configDir, "configDir").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 쓰기에 성공하면 true. 같은 값을 다시 써도 된다(실행 명령은 값만 본다). */
    public synchronized boolean publish(RuntimeConfig config) {
        int n = config.lab().maxParallelRuns();
        try {
            new ExperimentLimits(n, clock.instant()).write(configDir);
            log.info("실험 병렬 상한 파일 갱신: max-parallel-runs {} ({})", n, ExperimentLimits.file(configDir));
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("실험 병렬 상한 파일을 쓰지 못했습니다({}). 실험 실행 명령은 지난 값이나 기본 4를 씁니다: {}",
                    ExperimentLimits.file(configDir), e.getClass().getSimpleName());
            return false;
        }
    }

    public Path file() {
        return ExperimentLimits.file(configDir);
    }
}
