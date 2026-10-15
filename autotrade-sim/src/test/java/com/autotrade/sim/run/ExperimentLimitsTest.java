package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentLimitsTest {

    private static final Instant AT = Instant.parse("2026-10-15T01:02:03Z");

    @TempDir
    Path dir;

    @Test
    void 쓰고_읽으면_같고_임시_파일이_남지_않는다() throws Exception {
        new ExperimentLimits(2, AT).write(dir);

        assertThat(ExperimentLimits.read(dir)).contains(new ExperimentLimits(2, AT));
        assertThat(Files.readString(dir.resolve("experiment-state/limits")))
                .isEqualTo("format: experiment-limits-v1\nmax-parallel-runs: 2\nupdated-at: 2026-10-15T01:02:03Z\n");
        try (var files = Files.list(dir.resolve("experiment-state"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("limits");
        }
    }

    @Test
    void 다시_쓰면_바꿔_넣는다() throws Exception {
        new ExperimentLimits(2, AT).write(dir);
        new ExperimentLimits(8, AT.plusSeconds(1)).write(dir);

        assertThat(ExperimentLimits.read(dir).orElseThrow().maxParallelRuns()).isEqualTo(8);
    }

    @Test
    void 파일이_없으면_빈_값() throws Exception {
        assertThat(ExperimentLimits.read(dir)).isEmpty();
    }

    @Test
    void 범위_밖_값은_만들지도_읽지도_않는다() {
        assertThatThrownBy(() -> new ExperimentLimits(0, AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExperimentLimits(65, AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExperimentLimits.decode(text("65")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1~64");
        assertThatThrownBy(() -> ExperimentLimits.decode(text("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 형식이_틀리면_내용을_싣지_않고_거부한다() {
        String secretish = "SECRET-LIKE-VALUE";
        for (String bad : new String[] {
                "",
                "max-parallel-runs: 2\nupdated-at: 2026-10-15T01:02:03Z\n",
                "format: experiment-limits-v2\nmax-parallel-runs: 2\nupdated-at: 2026-10-15T01:02:03Z\n",
                text("2").replace("\n", "\r\n"),
                text("2").substring(0, text("2").length() - 1),
                text("two"),
                text("2") + "app-key: " + secretish + "\n",
                text("2") + "max-parallel-runs: 3\n",
                "format: experiment-limits-v1\nmax-parallel-runs: 2\n",
                "format: experiment-limits-v1\nmax-parallel-runs: 2\nupdated-at: yesterday\n",
                text("2") + "x".repeat(ExperimentLimits.MAX_BYTES)}) {
            assertThatThrownBy(() -> ExperimentLimits.decode(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).message().doesNotContain(secretish);
        }
    }

    @Test
    void 크기_상한을_넘는_파일과_폴더는_거부한다() throws Exception {
        Path f = dir.resolve("experiment-state/limits");
        Files.createDirectories(f.getParent());
        Files.writeString(f, text("2") + " ".repeat(ExperimentLimits.MAX_BYTES));
        assertThatThrownBy(() -> ExperimentLimits.read(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("너무 큽니다");

        Files.delete(f);
        Files.createDirectory(f);
        assertThatThrownBy(() -> ExperimentLimits.read(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일반 파일");
    }

    @Test
    void 위치는_상태_파일_폴더_옆이다() {
        assertThat(ExperimentLimits.file(dir)).isEqualTo(dir.resolve("experiment-state/limits").toAbsolutePath());
        assertThat(ExperimentLimits.file(dir).getParent()).isEqualTo(ExperimentJobFiles.in(dir).dir().getParent());
    }

    private static String text(String n) {
        return "format: experiment-limits-v1\nmax-parallel-runs: " + n + "\nupdated-at: 2026-10-15T01:02:03Z\n";
    }
}
