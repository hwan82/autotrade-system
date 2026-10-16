package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.app.lab.web.LocalOnlyRequestFilter;
import com.autotrade.sim.run.ExperimentJobFiles;
import com.autotrade.sim.run.ExperimentJobStatus;
import com.autotrade.sim.run.ExperimentProgress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** D-089: 실험 실행 명령의 진행 상태 조회와 취소 요청 API. 실험실은 상태 파일을 읽고 취소 요청 파일만 만든다. */
@SpringBootTest
@AutoConfigureMockMvc
class ExperimentJobControllerTest {

    private static final String RUNNING = "EXP-20261015-RUN01";
    private static final String DONE = "EXP-20261015-DONE1";
    private static final String STALE = "EXP-20261015-STALE";

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.resolve("config").toString());
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("strategies").toString());
        registry.add("autotrade.lab.experiments-dir", () -> dir.resolve("experiments").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LocalOnlyRequestFilter.CsrfToken token;

    @Autowired
    Clock clock;

    private ExperimentJobFiles files() {
        return ExperimentJobFiles.in(dir.resolve("config"));
    }

    private ExperimentJobStatus jobStatus(String job, ExperimentJobStatus.State state, Instant updated) {
        return new ExperimentJobStatus(job, "combination", state, 1234, updated, updated, 2, 1, 0,
                new ExperimentProgress(job, ExperimentProgress.Phase.RUNNING, 4, 3, 1, 0, 10, 15), "");
    }

    @BeforeEach
    void write() throws IOException {
        Instant now = clock.instant();
        ExperimentJobFiles f = files();
        Files.createDirectories(f.dir());
        try (var old = Files.list(f.dir())) {
            for (Path p : old.toList()) {
                Files.delete(p);
            }
        }
        f.write(jobStatus(RUNNING, ExperimentJobStatus.State.RUNNING, now).withExperimentsPlanned(4, now)); // D-098
        f.write(jobStatus(DONE, ExperimentJobStatus.State.COMPLETED, now.minusSeconds(600)));
        f.write(jobStatus(STALE, ExperimentJobStatus.State.RUNNING, now.minusSeconds(3600)));
        Files.writeString(f.dir().resolve("EXP-20261015-BROKEN.status"), "깨진 파일");
    }

    @Test
    void 목록은_갱신_시각_순이고_응답_없는_명령과_깨진_파일을_알린다() throws Exception {
        mvc.perform(get("/api/experiment-jobs").header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.unreadable").value(1))
                .andExpect(jsonPath("$.items[0].job").value(RUNNING))
                .andExpect(jsonPath("$.items[0].state").value("RUNNING"))
                .andExpect(jsonPath("$.items[0].permille").value(375))
                .andExpect(jsonPath("$.items[0].daysTotal").value(40))
                .andExpect(jsonPath("$.items[0].maxParallelRuns").value(2))
                .andExpect(jsonPath("$.items[0].experimentsPlanned").value(4))
                .andExpect(jsonPath("$.items[0].commandPermille").value(93)) // (0 × 1000 + 375) / 4
                .andExpect(jsonPath("$.items[1].job").value(DONE))
                .andExpect(jsonPath("$.items[1].experimentsPlanned").value(0))
                .andExpect(jsonPath("$.items[1].commandPermille").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[2].job").value(STALE))
                .andExpect(jsonPath("$.items[2].state").value("UNRESPONSIVE"))
                .andExpect(jsonPath("$.items[2].stale").value(true));
        mvc.perform(get("/api/experiment-jobs/{id}", RUNNING).header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelRequested").value(false));
        mvc.perform(get("/api/experiment-jobs/{id}", "EXP-20261015-NONE").header("Host", "127.0.0.1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/experiment-jobs/{id}", "bad id").header("Host", "127.0.0.1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 취소는_CSRF_토큰이_있어야_하고_실행_중인_명령에만_요청_파일을_만든다() throws Exception {
        mvc.perform(post("/api/experiment-jobs/{id}/cancel", RUNNING).header("Host", "127.0.0.1"))
                .andExpect(status().isForbidden());
        assertThat(files().cancelRequest(RUNNING)).doesNotExist();

        mvc.perform(post("/api/experiment-jobs/{id}/cancel", RUNNING).header("Host", "127.0.0.1")
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.cancelRequested").value(true));
        assertThat(files().cancelRequest(RUNNING)).exists();
        mvc.perform(post("/api/experiment-jobs/{id}/cancel", RUNNING).header("Host", "127.0.0.1")
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isAccepted()); // 다시 눌러도 같다

        for (String id : new String[] {DONE, STALE}) {
            mvc.perform(post("/api/experiment-jobs/{id}/cancel", id).header("Host", "127.0.0.1")
                            .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                    .andExpect(status().isConflict());
            assertThat(files().cancelRequest(id)).doesNotExist();
        }
        mvc.perform(post("/api/experiment-jobs/{id}/cancel", "EXP-20261015-NONE").header("Host", "127.0.0.1")
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 진행_상태_실시간_연결은_비동기로_열리고_루프백이_아니면_거절한다() throws Exception {
        mvc.perform(get("/api/experiment-jobs/stream").header("Host", "127.0.0.1").header("Accept", "text/event-stream"))
                .andExpect(request().asyncStarted());
        mvc.perform(get("/api/experiment-jobs/stream").header("Host", "evil.example")
                        .header("Accept", "text/event-stream"))
                .andExpect(status().isForbidden());
    }
}
