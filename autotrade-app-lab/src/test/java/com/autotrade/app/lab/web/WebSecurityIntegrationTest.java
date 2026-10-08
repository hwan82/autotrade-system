package com.autotrade.app.lab.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** UI-01: 루프백 Host, 변경 요청의 Origin·CSRF 검사, 보안 헤더, 정적 화면 서빙. */
@SpringBootTest
@AutoConfigureMockMvc
class WebSecurityIntegrationTest {

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

    private static final String BODY = "{\"values\":{\"lab.max-parallel-runs\":\"3\"}}";

    @Test
    void 화면과_API는_보안_헤더와_함께_나간다() throws Exception {
        mvc.perform(get("/").header("Host", "127.0.0.1:8480"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", LocalOnlyRequestFilter.CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        byte[] index = mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        // 정적 화면은 meta charset으로 UTF-8을 선언한다
        org.assertj.core.api.Assertions.assertThat(new String(index, java.nio.charset.StandardCharsets.UTF_8))
                .contains("<meta charset=\"utf-8\">", "실전 주문이 나가지 않습니다");
        mvc.perform(get("/js/app.js")).andExpect(status().isOk());
        mvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mode").value("LAB"))
                .andExpect(jsonPath("$.realOrderPath").value(false))
                .andExpect(jsonPath("$.csrfToken").value(token.value()));
    }

    @Test
    void 루프백이_아닌_Host는_조회도_거부한다() throws Exception {
        mvc.perform(get("/api/session").header("Host", "evil.example:8480"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HOST"));
        mvc.perform(get("/").header("Host", "attacker.test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 변경_요청은_CSRF_토큰이_없거나_다르면_거부한다() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF"));
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, "0".repeat(64)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_출처의_변경_요청은_토큰이_맞아도_거부한다() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value())
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN"));
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value())
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 같은_출처와_토큰이면_변경을_받는다() throws Exception {
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value())
                        .header("Origin", "http://127.0.0.1:8480")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk());
    }
}
