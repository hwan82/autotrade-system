package com.autotrade.app.lab.experiment;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.app.lab.web.LocalOnlyRequestFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** D-062: 실험 폴더 목록과 불러오기 API. 불러온 뒤 교차표·선정 결과 API가 실제 결과를 준다. */
@SpringBootTest
@AutoConfigureMockMvc
class ExperimentFolderControllerTest {

    private static final String ID = "EXP-20261011-CMB01";

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.resolve("config").toString());
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("strategies").toString());
        registry.add("autotrade.lab.experiments-dir", () -> dir.resolve("experiments").toString());
    }

    static SampleReports.ComboSample sample;

    @BeforeAll
    static void folder() throws IOException {
        sample = ExperimentFoldersTest.writeFolder(Files.createDirectories(dir.resolve("experiments")), ID);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LocalOnlyRequestFilter.CsrfToken token;

    @Test
    void 목록에서_불러오면_실험_화면_API가_실제_결과를_준다() throws Exception {
        mvc.perform(get("/api/experiment-folders").header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(ID))
                .andExpect(jsonPath("$[0].state").value("READY"));

        mvc.perform(post("/api/experiment-folders/{id}/load", ID).header("Host", "127.0.0.1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/experiment-folders/{id}/load", ID).header("Host", "127.0.0.1")
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID));
        mvc.perform(post("/api/experiment-folders/{id}/load", ID).header("Host", "127.0.0.1")
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/experiments/{id}/cross-table", ID).header("Host", "127.0.0.1"))
                .andExpect(status().isOk());
        String hash = sample.results().get(0).contentHash();
        mvc.perform(get("/api/experiments/{id}/selection-results/{hash}/evaluations", ID, hash).header("Host", "127.0.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(sample.results().get(0).evaluations().size()));
        mvc.perform(get("/api/experiment-folders").header("Host", "127.0.0.1"))
                .andExpect(jsonPath("$[0].state").value("LOADED"));
    }

    @Test
    void 없는_폴더와_잘못된_ID() throws Exception {
        mvc.perform(post("/api/experiment-folders/{id}/load", "NOPE-1").header("Host", "127.0.0.1")
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/experiment-folders/{id}/load", "-bad").header("Host", "127.0.0.1")
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()))
                .andExpect(status().isBadRequest());
    }
}
