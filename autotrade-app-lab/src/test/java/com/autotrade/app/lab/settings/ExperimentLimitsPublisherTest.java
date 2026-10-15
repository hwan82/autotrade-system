package com.autotrade.app.lab.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.sim.run.ExperimentLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실험실 설정의 병렬 상한을 실험 실행 명령의 상한 파일로 내보내기(D-094). */
class ExperimentLimitsPublisherTest {

    private static final Instant AT = Instant.parse("2026-10-15T01:00:00Z");

    @TempDir
    Path dir;

    private RuntimeConfig config(int parallel) throws Exception {
        Files.writeString(dir.resolve("runtime-config.json"), "{\"lab\": {\"max-parallel-runs\": " + parallel + "}}");
        return new ConfigLoader().load(ConfigPaths.in(dir)).config();
    }

    @Test
    void 설정값과_시각을_상한_파일에_쓴다() throws Exception {
        ExperimentLimitsPublisher publisher = new ExperimentLimitsPublisher(dir, Clock.fixed(AT, ZoneOffset.UTC));

        assertThat(publisher.publish(config(3))).isTrue();

        assertThat(ExperimentLimits.read(dir)).contains(new ExperimentLimits(3, AT));
        assertThat(publisher.file()).isEqualTo(dir.resolve("experiment-state/limits").toAbsolutePath());
        // 비밀값이 섞일 자리가 없다: 형식 줄, 상한, 시각 세 줄뿐
        assertThat(Files.readAllLines(publisher.file())).hasSize(3);
    }

    @Test
    void 쓰지_못하면_false이고_예외를_올리지_않는다() throws Exception {
        Files.writeString(dir.resolve("experiment-state"), "폴더 자리에 파일");
        ExperimentLimitsPublisher publisher = new ExperimentLimitsPublisher(dir, Clock.fixed(AT, ZoneOffset.UTC));

        assertThat(publisher.publish(config(2))).isFalse();
    }
}
