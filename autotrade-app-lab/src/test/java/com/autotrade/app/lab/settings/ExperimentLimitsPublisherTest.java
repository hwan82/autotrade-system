package com.autotrade.app.lab.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.config.ConfigLoader;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.ConfigSource;
import com.autotrade.config.LoadedConfig;
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

        RuntimeConfig c = config(3);
        assertThat(publisher.publish(() -> c)).isTrue();

        assertThat(ExperimentLimits.read(dir)).contains(new ExperimentLimits(3, AT));
        assertThat(publisher.file()).isEqualTo(dir.resolve("experiment-state/limits").toAbsolutePath());
        // 비밀값이 섞일 자리가 없다: 형식 줄, 상한, 시각 세 줄뿐
        assertThat(Files.readAllLines(publisher.file())).hasSize(3);
    }

    @Test
    void 설정_파일이_깨져_기본값으로_떴으면_기동_때_지난_상한을_덮지_않는다() throws Exception {
        ExperimentLimitsPublisher publisher = new ExperimentLimitsPublisher(dir, Clock.fixed(AT, ZoneOffset.UTC));
        new ExperimentLimits(1, AT.minusSeconds(60)).write(dir);
        Files.writeString(dir.resolve("runtime-config.json"), "{ 깨진 파일");
        LoadedConfig broken = new ConfigLoader().load(ConfigPaths.in(dir));
        assertThat(broken.source()).isEqualTo(ConfigSource.DEFAULT_FILE_INVALID);

        assertThat(publisher.publishAtStartup(broken, broken::config)).isFalse();
        assertThat(ExperimentLimits.read(dir).orElseThrow().maxParallelRuns()).isEqualTo(1);

        // 정상 파일로 뜨면 그 값으로 쓴다
        Files.writeString(dir.resolve("runtime-config.json"), "{\"lab\": {\"max-parallel-runs\": 2}}");
        LoadedConfig ok = new ConfigLoader().load(ConfigPaths.in(dir));
        assertThat(publisher.publishAtStartup(ok, ok::config)).isTrue();
        assertThat(ExperimentLimits.read(dir).orElseThrow().maxParallelRuns()).isEqualTo(2);
    }

    @Test
    void 값은_쓰는_잠금_안에서_읽는다() throws Exception {
        // 쓰기는 하나씩: 늦게 시작한 저장이 최신 값을 마지막에 쓴다(PR #121 architect F2)
        ExperimentLimitsPublisher publisher = new ExperimentLimitsPublisher(dir, Clock.fixed(AT, ZoneOffset.UTC));
        RuntimeConfig two = config(2);
        RuntimeConfig five = config(5);
        java.util.concurrent.atomic.AtomicReference<RuntimeConfig> current = new java.util.concurrent.atomic.AtomicReference<>(two);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread first = new Thread(() -> publisher.publish(() -> {
            RuntimeConfig seen = current.get(); // 2를 읽고 쓰기 전에 멈춤
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return seen;
        }));
        first.start();
        inside.await();
        current.set(five);
        Thread second = new Thread(() -> publisher.publish(current::get));
        second.start();
        // 둘째 저장이 잠금 앞에서 기다리는 것을 본 뒤 첫째를 놓는다. 잠금이 없으면 둘째가 먼저 5를 쓰고 첫째가 2로 덮는다
        while (second.getState() != Thread.State.BLOCKED && second.isAlive()) {
            Thread.onSpinWait();
        }
        release.countDown();
        first.join();
        second.join();

        assertThat(ExperimentLimits.read(dir).orElseThrow().maxParallelRuns()).isEqualTo(5);
    }

    @Test
    void 쓰지_못하면_false이고_예외를_올리지_않는다() throws Exception {
        Files.writeString(dir.resolve("experiment-state"), "폴더 자리에 파일");
        ExperimentLimitsPublisher publisher = new ExperimentLimitsPublisher(dir, Clock.fixed(AT, ZoneOffset.UTC));

        RuntimeConfig c = config(2);
        assertThat(publisher.publish(() -> c)).isFalse();
    }
}
