package com.autotrade.app.collector;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** HTTP GET 한 번. 테스트는 가짜 구현으로 실제 서버를 부르지 않는다. */
public interface HttpGetter {

    Response get(URI uri, Map<String, String> headers) throws IOException;

    /**
     * JSON 본문 POST 한 번. 한국투자 접근토큰 발급({@code /oauth2/tokenP})에만 쓴다(D-136). 다른 소스는 GET만 쓴다.
     */
    default Response post(URI uri, Map<String, String> headers, String jsonBody) throws IOException {
        throw new UnsupportedOperationException("POST를 지원하지 않는 HTTP 구현입니다");
    }

    record Response(int status, String body) {}
}
