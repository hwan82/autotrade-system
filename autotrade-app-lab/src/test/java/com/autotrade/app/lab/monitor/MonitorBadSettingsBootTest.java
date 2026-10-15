package com.autotrade.app.lab.monitor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 모니터링 임계 이름을 잘못 써도(QA PR #114 P1) 실험실이 뜨고 실전 운영 상태 API가 답하며, 모니터링만 사용 불가로 이유를
 * 보여 준다(QA R1).
 */
@SpringBootTest(properties = {"autotrade.lab.monitor.thresholds=cpu.sytem=ABOVE:70:90:3"})
@AutoConfigureMockMvc
class MonitorBadSettingsBootTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void configPath(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void 설정_오타에도_실험실이_뜨고_모니터링만_사용_불가다() throws Exception {
        mvc.perform(get("/api/real/status").header("Host", "127.0.0.1:8480")).andExpect(status().isOk());
        mvc.perform(get("/api/monitor").header("Host", "127.0.0.1:8480"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.problem").value(Matchers.containsString("cpu.sytem")));
        mvc.perform(get("/api/monitor/stream").header("Host", "127.0.0.1:8480")).andExpect(status().isConflict());
    }
}
