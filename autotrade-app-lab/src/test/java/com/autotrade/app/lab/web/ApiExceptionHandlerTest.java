package com.autotrade.app.lab.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** 2026-10-21 사용자 PC: 화면이 진행 스트림(SSE)을 닫은 뒤 쓰기 오류가 ERROR 로그와 두 번째 경고(JSON을 SSE에 못 씀)를 냈다. */
class ApiExceptionHandlerTest {

    @RestController
    static class StreamThatLostItsClient {
        @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        String stream() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to flush: java.io.IOException: 연결이 끊김");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StreamThatLostItsClient())
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void 화면이_스트림을_먼저_닫으면_응답_없이_조용히_끝낸다() throws Exception {
        // 처리하지 못하면 예외가 그대로 올라와 perform이 던진다
        MvcResult r = mvc.perform(get("/stream").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        assertThat(r.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
    }
}
