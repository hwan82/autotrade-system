package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentJobStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D-089: 상태 파일이 상한을 넘으면 오래된 작업부터 빠진다(PR #113 architect R3). */
class ExperimentJobsTest {

    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");

    @TempDir
    Path config;

    @Test
    void 상한을_넘으면_가장_최근_파일을_남기고_자른다() throws IOException {
        ExperimentJobFiles files = ExperimentJobFiles.in(config);
        int n = ExperimentJobs.MAX_FILES + 5;
        for (int i = 0; i < n; i++) {
            String job = String.format("EXP-%04d", i);
            Instant t = T0.plusSeconds(i);
            files.write(new ExperimentJobStatus(job, "combination", ExperimentJobStatus.State.COMPLETED, 1, t, t, 4, 1, 0,
                    null, ""));
            Files.setLastModifiedTime(files.status(job), FileTime.from(t));
        }

        ExperimentJobs.Listing listing = new ExperimentJobs(config, Clock.fixed(T0.plusSeconds(n), ZoneOffset.UTC)).list();

        assertThat(listing.truncated()).isTrue();
        assertThat(listing.items()).hasSize(ExperimentJobs.MAX_FILES);
        assertThat(listing.items().get(0).job()).isEqualTo(String.format("EXP-%04d", n - 1));
        assertThat(listing.items()).noneMatch(j -> j.job().equals("EXP-0000"));
    }
}
