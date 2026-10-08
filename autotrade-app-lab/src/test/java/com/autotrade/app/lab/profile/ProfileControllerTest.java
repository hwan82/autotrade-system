package com.autotrade.app.lab.profile;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.app.lab.web.LocalOnlyRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileControllerTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.resolve("config").toString());
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("strategies").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LocalOnlyRequestFilter.CsrfToken token;

    @Autowired
    ObjectMapper json;

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value())
                .content(json.writeValueAsString(body)));
    }

    @Test
    void 만들고_고치고_복제한다() throws Exception {
        String v1Hash = json.readTree(postJson("/api/profiles", new ProfileController.CreateRequest("gap-up",
                        com.autotrade.domain.market.Market.US, com.autotrade.strategy.TradingStyle.INTRADAY,
                        java.util.Map.of("entry.signal", "gap-up", "entry.limit-offset-pct", "0.2"), null, "시작"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andReturn().getResponse().getContentAsString()).get("hash").asText();

        postJson("/api/profiles/gap-up/versions", new ProfileController.VersionRequest(1,
                java.util.Map.of("entry.signal", "gap-up", "entry.limit-offset-pct", "0.3"), "여유폭 확대"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.basedOn").value("gap-up@1"))
                .andExpect(jsonPath("$.hash").value(not(v1Hash)));

        // 이미 v2가 있으니 v1을 기준으로 한 편집은 충돌
        postJson("/api/profiles/gap-up/versions", new ProfileController.VersionRequest(1,
                java.util.Map.of("entry.signal", "x"), ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        postJson("/api/profiles", new ProfileController.CreateRequest("gap-up-kr",
                com.autotrade.domain.market.Market.KRX, com.autotrade.strategy.TradingStyle.INTRADAY,
                java.util.Map.of("entry.signal", "gap-up"), "gap-up@2", "국내판"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.basedOn").value("gap-up@2"))
                .andExpect(jsonPath("$.market").value("KRX"));

        mvc.perform(get("/api/profiles")).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("gap-up"))
                .andExpect(jsonPath("$[0].versions", hasSize(2)));
        mvc.perform(get("/api/profiles/gap-up")).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/profiles/gap-up/versions/1")).andExpect(jsonPath("$.hash").value(v1Hash))
                .andExpect(jsonPath("$.parameters['entry.limit-offset-pct']").value("0.2"));
    }

    @Test
    void 잘못된_요청은_400_없는_것은_404() throws Exception {
        postJson("/api/profiles", java.util.Map.of("id", "x")).andExpect(status().isBadRequest());
        postJson("/api/profiles", java.util.Map.of("id", "x", "market", "MARS", "style", "SWING"))
                .andExpect(status().isBadRequest());
        postJson("/api/profiles", new ProfileController.CreateRequest("../bad",
                com.autotrade.domain.market.Market.KRX, com.autotrade.strategy.TradingStyle.SWING,
                java.util.Map.of(), null, null)).andExpect(status().isBadRequest());
        postJson("/api/profiles", new ProfileController.CreateRequest("clone",
                com.autotrade.domain.market.Market.KRX, com.autotrade.strategy.TradingStyle.SWING,
                java.util.Map.of(), "nothing@9", null)).andExpect(status().isBadRequest());
        postJson("/api/profiles/nothing/versions", new ProfileController.VersionRequest(1, java.util.Map.of("a", "1"), null))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/profiles/nothing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/profiles/nothing/versions/1")).andExpect(status().isNotFound());
    }
}
