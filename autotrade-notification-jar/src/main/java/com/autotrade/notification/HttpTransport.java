package com.autotrade.notification;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** 채널이 쓰는 HTTP POST 한 번. 시험은 가짜 구현을 넣고, 운영은 {@link JdkHttpTransport}를 쓴다. */
public interface HttpTransport {

    /**
     * 본문을 POST하고 상태와 본문(앞부분만)을 돌려준다. 리다이렉트는 따르지 않는다.
     *
     * @throws IOException 연결 실패·시간 초과(구현은 예외 문구에 URI를 넣지 않는 것을 보장하지 않으므로 채널은 문구를 쓰지 않는다)
     */
    HttpResult post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException;

    /**
     * 응답.
     *
     * @param status HTTP 상태
     * @param body 본문 앞부분({@link JdkHttpTransport#MAX_BODY_BYTES}까지, UTF-8)
     */
    record HttpResult(int status, String body) {}
}
