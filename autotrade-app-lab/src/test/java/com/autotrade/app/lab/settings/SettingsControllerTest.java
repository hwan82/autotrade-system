package com.autotrade.app.lab.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.app.lab.web.LocalOnlyRequestFilter;
import com.autotrade.config.KakaoMode;
import com.autotrade.config.RuntimeConfigHolder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UI-04: default 값 표시, 즉시 반영 키 변경(write-through), 비밀값은 화면·응답·로그에 평문으로 나오지 않음.
 * 테스트 비밀값은 실제 값이 아닌 자리표시자다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SettingsControllerTest {

    static final String BROKER_KEY = "PLACEHOLDER-KIS-APPKEY-0001";
    static final String BROKER_SECRET = "PLACEHOLDER-KIS-SECRET-0002";
    static final String ACCOUNT_NO = "PLACEHOLDER-ACCT-0003";
    static final String OLD_TOKEN = "PLACEHOLDER-KAKAO-TOKEN-0004";
    static final String NEW_WEBHOOK = "PLACEHOLDER-SLACK-WEBHOOK-0005";

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        Path config = dir.resolve("config");
        try {
            Files.createDirectories(config);
            Files.writeString(config.resolve("runtime-config.json"), """
                    {"real-trading": {"accounts": [{"id": "KIS-KR-1", "broker": "KIS", "market": "KRX"}]}}
                    """);
            Files.writeString(config.resolve("secrets.json"), """
                    {"brokers": {"kis": {"app-key": "%s", "app-secret": "%s"}},
                     "notification": {"kakao": {"access-token": "%s"}},
                     "real-trading": {"account-numbers": {"KIS-KR-1": "%s"}}}
                    """.formatted(BROKER_KEY, BROKER_SECRET, OLD_TOKEN, ACCOUNT_NO));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("autotrade.config-dir", config::toString);
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("strategies").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LocalOnlyRequestFilter.CsrfToken token;

    @Autowired
    RuntimeConfigHolder holder;

    private ResultActions putJson(String body) throws Exception {
        return mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON)
                .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()).content(body));
    }

    private static void assertNoPlaintext(String text) {
        assertThat(text).doesNotContain(BROKER_KEY, BROKER_SECRET, ACCOUNT_NO, OLD_TOKEN, NEW_WEBHOOK, "PLACEHOLDER");
    }

    @Test
    void 조회_응답에는_비밀값이_설정_여부와_마스크로만_나온다() throws Exception {
        String body = mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].items[0].key").value("lab.max-parallel-runs"))
                .andExpect(jsonPath("$.groups[0].items[0].value").value("4"))
                .andExpect(jsonPath("$.groups[0].items[0].defaultValue").value("4"))
                .andExpect(jsonPath("$.groups[0].items[0].editable").value(true))
                .andExpect(jsonPath("$.groups[?(@.id=='brokers')].items[0].value").value("****"))
                .andExpect(jsonPath("$.groups[?(@.id=='brokers')].items[0].configured").value(true))
                .andExpect(jsonPath("$.groups[?(@.id=='brokers')].items[0].editable").value(false))
                .andExpect(jsonPath("$.groups[?(@.id=='brokers')].items[2].configured").value(false))
                .andExpect(jsonPath("$.groups[?(@.id=='real-trading')].items[1].key")
                        .value("real-trading.account-numbers.KIS-KR-1"))
                .andReturn().getResponse().getContentAsString();
        assertNoPlaintext(body);
        String session = mvc.perform(get("/api/session")).andReturn().getResponse().getContentAsString();
        assertNoPlaintext(session);
        assertThat(session).contains("\"keyStatus\":\"CONFIGURED\"", "\"accountNumberStatus\":\"CONFIGURED\"");
    }

    @Test
    void 즉시_반영_키를_바꾸면_파일에_먼저_쓰고_메모리를_바꾼다() throws Exception {
        putJson("{\"values\":{\"lab.max-parallel-runs\":\"2\",\"lab.default-seed-capital-krw\":\"5000000\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].items[0].value").value("2"))
                .andExpect(jsonPath("$.groups[0].items[0].defaultValue").value("4"));
        assertThat(holder.current().lab().maxParallelRuns()).isEqualTo(2);
        assertThat(holder.current().lab().defaultSeedCapitalKrw()).isEqualTo(5_000_000L);
        assertThat(Files.readString(dir.resolve("config/runtime-config.json"))).contains("\"max-parallel-runs\" : 2");
    }

    @Test
    void 비밀값은_쓰기_전용이고_응답_로그_runtime_config에_평문이_없다(CapturedOutput output) throws Exception {
        String body = putJson("{\"values\":{\"notification.slack.webhook-url\":\"" + NEW_WEBHOOK
                + "\",\"notification.slack.enabled\":\"true\"}}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNoPlaintext(body);
        assertThat(holder.current().notification().slack().webhookUrl().reveal()).isEqualTo(NEW_WEBHOOK);
        assertThat(Files.readString(dir.resolve("config/secrets.json"))).contains(NEW_WEBHOOK);
        assertThat(Files.readString(dir.resolve("config/runtime-config.json"))).doesNotContain(NEW_WEBHOOK);
        assertThat(output.getAll()).doesNotContain(NEW_WEBHOOK, OLD_TOKEN, BROKER_KEY);
    }

    @Test
    void 비밀값_빈_입력은_그대로_두고_지우기는_명시해야_한다() throws Exception {
        putJson("{\"values\":{\"notification.kakao.access-token\":\"\"}}").andExpect(status().isOk());
        assertThat(holder.current().notification().kakao().accessToken().reveal()).isEqualTo(OLD_TOKEN);
        putJson("{\"clearSecrets\":[\"notification.kakao.access-token\"]}").andExpect(status().isOk());
        assertThat(holder.current().notification().kakao().accessToken().isPresent()).isFalse();
        putJson("{\"clearSecrets\":[\"lab.max-parallel-runs\"]}").andExpect(status().isBadRequest());
    }

    @Test
    void 증권사_키_실전_계좌_시세_소스는_이_화면에서_바꿀_수_없다() throws Exception {
        putJson("{\"values\":{\"brokers.kis.app-key\":\"x\"}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("brokers.kis.app-key")));
        putJson("{\"values\":{\"real-trading.accounts\":\"[]\"}}").andExpect(status().isBadRequest());
        putJson("{\"values\":{\"market-data.mode\":\"FAILOVER\"}}").andExpect(status().isBadRequest());
        putJson("{\"values\":{\"real-trading.enabled\":\"true\"}}").andExpect(status().isBadRequest());
        assertThat(holder.current().brokers().kis().appKey().reveal()).isEqualTo(BROKER_KEY);
    }

    @Test
    void 범위를_벗어나거나_형식이_틀린_값은_거절하고_입력값을_되돌려_주지_않는다() throws Exception {
        putJson("{\"values\":{\"lab.max-parallel-runs\":\"65\"}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("1~64")));
        putJson("{\"values\":{\"lab.max-parallel-runs\":\"many\"}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("lab.max-parallel-runs는 정수여야 합니다"));
        putJson("{\"values\":{\"notification.slack.enabled\":\"true\"}}").andExpect(status().isBadRequest());
        String body = putJson("{\"values\":{\"notification.kakao.access-token\":\"" + NEW_WEBHOOK + "\\n\"}}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertNoPlaintext(body);
        String unknown = putJson("{\"values\":{\"<script>" + NEW_WEBHOOK + "\":\"1\"}}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertNoPlaintext(unknown);
        assertThat(holder.current().lab().maxParallelRuns()).isEqualTo(4);
    }

    @Test
    void 유료인_카카오_알림톡은_비용_확인이_있어야_켤_수_있다() throws Exception {
        putJson("{\"values\":{\"notification.kakao.mode\":\"BIZ\"}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAID_CONFIRMATION_REQUIRED"));
        assertThat(holder.current().notification().kakao().mode()).isEqualTo(KakaoMode.ME);
        putJson("{\"values\":{\"notification.kakao.mode\":\"BIZ\"},\"acknowledgePaid\":true}").andExpect(status().isOk());
        assertThat(holder.current().notification().kakao().mode()).isEqualTo(KakaoMode.BIZ);
    }

    @Test
    void 빈_변경은_거절한다() throws Exception {
        putJson("{}").andExpect(status().isBadRequest());
        putJson("not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"));
    }
}
