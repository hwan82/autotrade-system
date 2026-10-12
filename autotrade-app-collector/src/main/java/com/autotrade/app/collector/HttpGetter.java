package com.autotrade.app.collector;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** HTTP GET 한 번. 테스트는 가짜 구현으로 실제 서버를 부르지 않는다. */
public interface HttpGetter {

    Response get(URI uri, Map<String, String> headers) throws IOException;

    record Response(int status, String body) {}
}
