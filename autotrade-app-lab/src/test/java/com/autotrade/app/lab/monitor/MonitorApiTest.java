package com.autotrade.app.lab.monitor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 실험실에 붙은 모니터링 API: 기본 임계가 보이고, SSE가 열리고, 루프백이 아닌 Host는 막힌다. */
@SpringBootTest(properties = {"autotrade.lab.monitor.thresholds=cpu.system=ABOVE:70:90:2"})
@AutoConfigureMockMvc
class MonitorApiTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void configPath(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void 상태_조회는_임계와_덮어쓴_값을_보여_준다() throws Exception {
        mvc.perform(get("/api/monitor").header("Host", "127.0.0.1:8480"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.intervalSeconds").value(5))
                .andExpect(jsonPath("$.thresholds[?(@.metric == 'cpu.system')].warn").value(70.0))
                .andExpect(jsonPath("$.thresholds[?(@.metric == 'jvm.heap.used')].critical").value(95.0));
    }

    @Test
    void 실시간_연결은_비동기로_열린다() throws Exception {
        mvc.perform(get("/api/monitor/stream").header("Host", "localhost:8480").header("Accept", "text/event-stream"))
                .andExpect(request().asyncStarted());
    }

    @Test
    void 루프백이_아닌_Host는_거절한다() throws Exception {
        mvc.perform(get("/api/monitor").header("Host", "evil.example")).andExpect(status().isForbidden());
    }
}
