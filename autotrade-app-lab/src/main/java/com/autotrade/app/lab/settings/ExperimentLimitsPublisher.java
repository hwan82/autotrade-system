package com.autotrade.app.lab.settings;

import com.autotrade.config.ConfigSource;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.sim.run.ExperimentLimits;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 실험실 설정 {@code lab.max-parallel-runs}를 실험 실행 명령이 읽는 상한 파일({@link ExperimentLimits},
 * {@code <설정 폴더>/experiment-state/limits})로 내보낸다(D-094). 실험실 기동 때와 설정 화면 저장 뒤에 쓴다.
 *
 * <p>실행 명령은 설정 로더를 쓰지 못하므로(보호 규칙 {@code EXPERIMENT_COMMAND_READS_NO_PROCESS_CONFIG}, D-063) 비밀값이 없는 이
 * 파일만 본다. 쓰기에 실패해도 설정 저장은 되돌리지 않는다: 실행 명령은 지난 파일 값(없으면 기본 4)으로 돌고, 실패는 경고 로그와
 * 반환값으로 알린다.
 *
 * <p>기동 때 {@code runtime-config.json}이 깨져 내장 기본값으로 떴으면({@link ConfigSource#DEFAULT_FILE_INVALID}) 쓰지 않는다. 사용자가
 * 낮춰 둔 상한이 기본값으로 덮이지 않게 지난 파일을 그대로 둔다(PR #121 architect F1).
 */
public final class ExperimentLimitsPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExperimentLimitsPublisher.class);

    private final Path configDir;
    private final Clock clock;

    public ExperimentLimitsPublisher(Path configDir, Clock clock) {
        this.configDir = Objects.requireNonNull(configDir, "configDir").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 기동 때 한 번. 설정 파일이 깨져 기본값으로 떴으면 쓰지 않고 false. */
    public boolean publishAtStartup(LoadedConfig loaded, Supplier<RuntimeConfig> current) {
        if (loaded.source() == ConfigSource.DEFAULT_FILE_INVALID) {
            log.warn("runtime-config.json이 깨져 기본값으로 떴으므로 실험 병렬 상한 파일({})을 고치지 않습니다", file());
            return false;
        }
        return publish(current);
    }

    /**
     * 쓰기에 성공하면 true. 값은 잠금 안에서 {@code current}로 읽는다: 두 저장이 겹쳐도 마지막 쓰기가 최신 설정을 쓴다(PR #121
     * architect F2). 같은 값을 다시 써도 된다(실행 명령은 값만 본다).
     */
    public synchronized boolean publish(Supplier<RuntimeConfig> current) {
        int n = current.get().lab().maxParallelRuns();
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
