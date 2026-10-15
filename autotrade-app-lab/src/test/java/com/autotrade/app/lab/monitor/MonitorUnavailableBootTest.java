package com.autotrade.app.lab.monitor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 수집원 생성이 네이티브 적재 실패(UnsatisfiedLinkError)를 내도 실험실이 뜨고, 실전 운영 화면 API와 모니터링 API가 답한다
 * (risk-ops PR #114 R1: 화면용 부가 기능이 내리기·PANIC SELL 화면을 막으면 안 된다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MonitorUnavailableBootTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void configPath(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.toString());
    }

    @TestConfiguration
    static class BrokenNative {
        @Bean
        @Primary
        MonitorAgentFactory brokenMonitorAgentFactory() {
            return settings -> {
                throw new UnsatisfiedLinkError("jnidispatch 적재 실패 주입");
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void 실험실이_뜨고_실전_운영_상태와_모니터링_사용_불가가_응답한다() throws Exception {
        mvc.perform(get("/api/real/status").header("Host", "127.0.0.1:8480")).andExpect(status().isOk());
        mvc.perform(get("/api/monitor").header("Host", "127.0.0.1:8480"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.problem").value(org.hamcrest.Matchers.containsString("UnsatisfiedLinkError")));
        mvc.perform(get("/api/monitor/stream").header("Host", "127.0.0.1:8480")).andExpect(status().isConflict());
    }
}
