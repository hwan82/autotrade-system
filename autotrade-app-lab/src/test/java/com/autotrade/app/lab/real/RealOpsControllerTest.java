package com.autotrade.app.lab.real;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autotrade.app.lab.web.LocalOnlyRequestFilter;
import com.autotrade.control.ControlCodes;
import com.autotrade.control.ControlCommand;
import com.autotrade.control.ControlRequest;
import com.autotrade.control.ControlResponse;
import com.autotrade.control.ControlServer;
import com.autotrade.control.EngineStatus;
import com.autotrade.control.PanicStepResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * UI-05·06: 운영 화면 API는 실전 엔진 명령 채널로 넘기기만 하고, 비밀번호를 응답·로그에 남기지 않는다.
 * 엔진 대신 같은 소켓 위치에 가짜 명령 채널 서버를 띄운다. 비밀번호는 자리표시자다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RealOpsControllerTest {

    static final String PASSWORD = "PLACEHOLDER-OPERATOR-PW";

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(dir.resolve("c"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Unix 소켓 경로 길이 제한(약 100바이트) 때문에 짧은 폴더 이름을 쓴다
        registry.add("autotrade.config-dir", () -> dir.resolve("c").toString());
        registry.add("autotrade.lab.strategies-dir", () -> dir.resolve("s").toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LocalOnlyRequestFilter.CsrfToken token;

    private final List<ControlRequest> received = new CopyOnWriteArrayList<>();
    private ControlServer engine;

    private void startEngine(Function<ControlRequest, ControlResponse> handler) throws IOException {
        engine = ControlServer.start(dir.resolve("c").resolve("run").resolve("engine-control.sock"), r -> {
            received.add(r);
            return handler.apply(r);
        });
    }

    @AfterEach
    void stopEngine() throws IOException {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        // 가짜 엔진은 이 테스트 JVM의 PID를 잠금 파일에 적는다. 남겨 두면 다음 테스트가 "엔진 살아 있음"으로 본다
        Files.deleteIfExists(dir.resolve("c").resolve("run").resolve("engine.lock"));
        received.clear();
    }

    static EngineStatus snapshot(String state) {
        return new EngineStatus(true, null, List.of(
                new EngineStatus.AccountStatus("KIS-KR-1", "KIS", "KRX", state, List.of(), true)));
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value()).content(body));
    }

    @Test
    void 엔진이_꺼져_있으면_상태는_연결_안_됨으로_보이고_명령은_503이다() throws Exception {
        mvc.perform(get("/api/real/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineState").value("NOT_RUNNING"))
                .andExpect(jsonPath("$.engineReachable").value(false))
                .andExpect(jsonPath("$.asOf").isNotEmpty())
                .andExpect(jsonPath("$.status").doesNotExist());
        postJson("/api/real/accounts/KIS-KR-1/deactivate", "{}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ControlCodes.ENGINE_UNREACHABLE))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("다시 보내지 말고")));
    }

    @Test
    void 상태는_엔진_스냅샷을_그대로_보여_준다() throws Exception {
        startEngine(r -> ControlResponse.accepted("", snapshot("OPEN"), List.of()));

        mvc.perform(get("/api/real/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineState").value("UP"))
                .andExpect(jsonPath("$.engineReachable").value(true))
                .andExpect(jsonPath("$.status.accounts[0].id").value("KIS-KR-1"))
                .andExpect(jsonPath("$.status.accounts[0].state").value("OPEN"));
    }

    @Test
    void 엔진이_답하지_않으면_상태를_알_수_없음으로_보여_준다() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        startEngine(r -> {
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ControlResponse.accepted("", snapshot("OPEN"), List.of());
        });
        try {
            mvc.perform(get("/api/real/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.engineState").value("UNKNOWN"))
                    .andExpect(jsonPath("$.engineReachable").value(false))
                    .andExpect(jsonPath("$.status").doesNotExist());
        } finally {
            release.countDown();
        }
    }

    @Test
    void 확인에_실패한_PANIC도_보호_모드_고정_결과를_돌려준다() throws Exception {
        startEngine(r -> new ControlResponse(false, ControlCodes.LOCKED, "잠겼습니다. 보호 모드 고정만 했습니다",
                snapshot("PROTECTIVE"), List.of(
                        new PanicStepResult("KIS-KR-1", "PROTECTIVE_LOCK", "DONE", "OPEN → PROTECTIVE"),
                        new PanicStepResult("KIS-KR-1", "SELL", "SKIPPED", "2차 확인을 통과하지 않아 하지 않았습니다"))));

        postJson("/api/real/accounts/KIS-KR-1/panic", "{\"typedConfirmation\":\"KIS-KR-1\",\"password\":\"x\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(ControlCodes.LOCKED))
                .andExpect(jsonPath("$.status.accounts[0].state").value("PROTECTIVE"))
                .andExpect(jsonPath("$.steps[0].outcome").value("DONE"));
    }

    @Test
    void 활성화는_확인_문구와_비밀번호를_엔진에_넘기고_응답과_로그에는_남기지_않는다(CapturedOutput output)
            throws Exception {
        startEngine(r -> ControlResponse.accepted("활성화 실패: 증권사 연동 전", snapshot("OFF"), List.of()));

        String body = postJson("/api/real/accounts/KIS-KR-1/activate",
                "{\"typedConfirmation\":\"KIS-KR-1\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("활성화 실패: 증권사 연동 전"))
                .andReturn().getResponse().getContentAsString();

        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.command()).isEqualTo(ControlCommand.ACTIVATE);
            assertThat(r.account()).isEqualTo("KIS-KR-1");
            assertThat(r.typedConfirmation()).isEqualTo("KIS-KR-1");
            assertThat(r.password()).isEqualTo(PASSWORD);
        });
        assertThat(body).doesNotContain(PASSWORD);
        assertThat(output.getAll()).doesNotContain(PASSWORD).contains("실전 제어 명령 ACTIVATE 결과 OK");
    }

    @Test
    void 엔진의_거절은_코드별_HTTP_상태로_바꾼다() throws Exception {
        String[] code = {ControlCodes.CONFIRMATION_FAILED};
        startEngine(r -> ControlResponse.rejected(code[0], "비밀번호 또는 확인 문구가 맞지 않습니다", snapshot("OFF")));

        String confirm = "{\"typedConfirmation\":\"x\",\"password\":\"" + PASSWORD + "\"}";
        postJson("/api/real/accounts/KIS-KR-1/activate", confirm).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ControlCodes.CONFIRMATION_FAILED));
        code[0] = ControlCodes.LOCKED;
        postJson("/api/real/panic", confirm).andExpect(status().isLocked());
        code[0] = ControlCodes.INVALID_STATE;
        postJson("/api/real/accounts/KIS-KR-1/panic", confirm).andExpect(status().isConflict());
        code[0] = ControlCodes.UNKNOWN_ACCOUNT;
        postJson("/api/real/accounts/NOPE/activate", confirm).andExpect(status().isNotFound());
        code[0] = ControlCodes.INTERNAL;
        postJson("/api/real/accounts/KIS-KR-1/deactivate", "{}").andExpect(status().isBadGateway());
        code[0] = ControlCodes.BUSY;
        postJson("/api/real/accounts/KIS-KR-1/activate", confirm).andExpect(status().isServiceUnavailable());
        code[0] = ControlCodes.STALE;
        postJson("/api/real/accounts/KIS-KR-1/activate", confirm).andExpect(status().isConflict());
        code[0] = ControlCodes.BAD_REQUEST;
        postJson("/api/real/accounts/KIS-KR-1/activate", confirm).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID"));
    }

    @Test
    void PANIC_결과는_단계별로_돌려준다() throws Exception {
        startEngine(r -> ControlResponse.accepted("PANIC SELL 처리", snapshot("PROTECTIVE"), List.of(
                new PanicStepResult("KIS-KR-1", "PROTECTIVE_LOCK", "DONE", "OPEN → PROTECTIVE"),
                new PanicStepResult("KIS-KR-1", "SELL", "NOT_AVAILABLE", "증권사 주문 연동 전"))));

        postJson("/api/real/panic", "{\"typedConfirmation\":\"전체 청산\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.steps[0].step").value("PROTECTIVE_LOCK"))
                .andExpect(jsonPath("$.steps[1].outcome").value("NOT_AVAILABLE"));
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.command()).isEqualTo(ControlCommand.PANIC_ALL);
            assertThat(r.typedConfirmation()).isEqualTo(ControlRequest.PANIC_ALL_PHRASE);
        });
    }

    @Test
    void 내리기는_사유만_넘긴다() throws Exception {
        startEngine(r -> ControlResponse.accepted("KIS-KR-1 OPEN → PROTECTIVE", snapshot("PROTECTIVE"), List.of()));

        postJson("/api/real/accounts/KIS-KR-1/deactivate", "{\"reason\":\"점검\"}").andExpect(status().isOk());
        postJson("/api/real/accounts/KIS-KR-1/deactivate", "").andExpect(status().isOk());

        assertThat(received).extracting(ControlRequest::reason).containsExactly("점검", null);
        assertThat(received).allSatisfy(r -> assertThat(r.password()).isNull());
    }

    @Test
    void CSRF_토큰이나_같은_출처가_아니면_엔진까지_가지_않는다() throws Exception {
        startEngine(r -> ControlResponse.accepted("", snapshot("OFF"), List.of()));
        String confirm = "{\"typedConfirmation\":\"KIS-KR-1\",\"password\":\"" + PASSWORD + "\"}";

        mvc.perform(post("/api/real/accounts/KIS-KR-1/activate").contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF"));
        mvc.perform(post("/api/real/panic").contentType(MediaType.APPLICATION_JSON)
                        .header(LocalOnlyRequestFilter.CSRF_HEADER, token.value())
                        .header("Origin", "http://evil.example").content(confirm))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN"));
        assertThat(received).isEmpty();
    }

    @Test
    void 계좌_별칭_형식이_틀리거나_입력이_너무_길면_엔진에_보내지_않는다() throws Exception {
        startEngine(r -> ControlResponse.accepted("", snapshot("OFF"), List.of()));

        postJson("/api/real/accounts/KIS%20KR/activate", "{\"typedConfirmation\":\"x\",\"password\":\"y\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/real/panic", "{\"typedConfirmation\":\"x\",\"password\":\"" + "p".repeat(2000) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("입력이 너무 깁니다"));
        assertThat(received).isEmpty();
    }

    @Test
    void 요청_객체_문자열에도_비밀번호가_없다() {
        assertThat(new RealOpsController.ConfirmRequest("KIS-KR-1", PASSWORD).toString()).doesNotContain(PASSWORD);
    }
}
