package com.autotrade.marketdata.live;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** 토큰 발급·폴링용 HTTP 포트. 운영은 {@link JdkHttpPort}, 테스트는 가짜 구현. */
public interface HttpPort {

    Response post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException;

    Response get(URI uri, Map<String, String> headers) throws IOException, InterruptedException;

    /** 응답. 본문에는 토큰이 들어 있을 수 있으니 로그·예외 메시지에 넣지 않는다. */
    record Response(int status, String body, Map<String, String> headers) {
        public Response {
            body = body == null ? "" : body;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }
}
