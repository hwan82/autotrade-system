package com.autotrade.app.lab.selection;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.app.lab.web.LocalOnlyRequestFilter;
import com.autotrade.domain.market.Market;
import com.autotrade.strategy.TradingStyle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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

/** UI-03 선정 프로필 API: 만들기·고치기·복제, 검증 실패 400, 충돌 409, 카탈로그 목록. 필드 계약 테스트를 겸한다. */
@SpringBootTest
@AutoConfigureMockMvc
class SelectionProfileControllerTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("autotrade.config-dir", () -> dir.resolve("config").toString());
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("strategies").toString());
        registry.add("autotrade.lab.selections-dir", () -> dir.resolve("selections").toString());
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

    private static Map<String, String> fin(String opMarginGrowth) {
        Map<String, String> p = new HashMap<>();
        p.put("hypothesis", "재무 개선이 가격에 늦게 반영된다");
        p.put("refresh", "POST_CLOSE_WEEKLY");
        p.put("max-symbols", "50");
        p.put("filter.kinds", "STOCK");
        p.put("gate.01.metric", "op-margin-growth");
        p.put("gate.01.years", "3");
        p.put("gate.01.op", "GE");
        p.put("gate.01.value", opMarginGrowth);
        p.put("notes.source", "D-043 (가)");
        return p;
    }

    @Test
    void 만들고_고치고_복제한다() throws Exception {
        String v1Hash = json.readTree(postJson("/api/selections", new SelectionProfileController.CreateRequest(
                        "kr-fin", Market.KRX, TradingStyle.POSITION, fin("0.5"), null, "시작"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.style").value("POSITION"))
                .andExpect(jsonPath("$.refresh").value("POST_CLOSE_WEEKLY"))
                .andExpect(jsonPath("$.maxSymbols").value(50))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.catalogProblem").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("hash").asText();

        postJson("/api/selections/kr-fin/versions", new SelectionProfileController.VersionRequest(1, fin("0.4"), "완화"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.basedOn").value("kr-fin@1"))
                .andExpect(jsonPath("$.hash").value(not(v1Hash)));

        postJson("/api/selections/kr-fin/versions", new SelectionProfileController.VersionRequest(1, fin("0.3"), ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        postJson("/api/selections", new SelectionProfileController.CreateRequest(
                "kr-fin-copy", Market.KRX, TradingStyle.POSITION, fin("0.5"), "kr-fin@2", "복제"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.basedOn").value("kr-fin@2"));

        mvc.perform(get("/api/selections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value("kr-fin"))
                .andExpect(jsonPath("$.items[0].versions", hasSize(2)));
        mvc.perform(get("/api/selections/kr-fin")).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/selections/kr-fin/versions/1"))
                .andExpect(jsonPath("$.hash").value(v1Hash))
                .andExpect(jsonPath("$.parameters['gate.01.value']").value("0.5"));
        mvc.perform(get("/api/selections/nope")).andExpect(status().isNotFound());
    }

    @Test
    void 카탈로그_검사에_걸리면_400이고_파일을_남기지_않는다() throws Exception {
        Map<String, String> bad = fin("0.5");
        bad.put("gate.01.metric", "per");
        postJson("/api/selections", new SelectionProfileController.CreateRequest(
                "kr-bad", Market.KRX, TradingStyle.POSITION, bad, null, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"));
        // 단타 선정에 장 마감 뒤 갱신은 쓸 수 없다
        postJson("/api/selections", new SelectionProfileController.CreateRequest(
                "kr-bad", Market.KRX, TradingStyle.INTRADAY, fin("0.5"), null, ""))
                .andExpect(status().isBadRequest());
        postJson("/api/selections", new SelectionProfileController.CreateRequest(
                "kr-bad", Market.KRX, TradingStyle.POSITION, fin("0.5"), "missing@1", ""))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/selections/kr-bad")).andExpect(status().isNotFound());
    }

    @Test
    void 카탈로그는_지표_연산_갱신_주기_상품_유형을_준다() throws Exception {
        mvc.perform(get("/api/selection-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics[?(@.key == 'op-margin-growth')].param").value(hasItem("YEARS")))
                .andExpect(jsonPath("$.metrics[?(@.key == 'volume-ratio')].kind").value(hasItem("BARS")))
                .andExpect(jsonPath("$.ops", hasSize(4)))
                .andExpect(jsonPath("$.refresh[?(@.name == 'PRE_OPEN_DAILY')].styles[0]").value(hasItem("INTRADAY")))
                .andExpect(jsonPath("$.refresh[?(@.name == 'POST_CLOSE_WEEKLY')].styles[*]").value(hasItem("POSITION")))
                .andExpect(jsonPath("$.kinds").value(hasItem("STOCK")));
    }

    @Test
    void 저장은_CSRF_토큰이_있어야_한다() throws Exception {
        mvc.perform(post("/api/selections").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
