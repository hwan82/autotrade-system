package com.autotrade.app.collector;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** 실제 서버를 부르지 않는 HTTP. 호출한 URL·헤더를 남긴다. */
final class FakeHttp implements HttpGetter {

    final List<URI> calls = new ArrayList<>();
    final List<Map<String, String>> headers = new ArrayList<>();
    private final BiFunction<URI, Integer, Response> answer;

    FakeHttp(BiFunction<URI, Integer, Response> answer) {
        this.answer = answer;
    }

    @Override
    public Response get(URI uri, Map<String, String> h) throws IOException {
        calls.add(uri);
        headers.add(h);
        Response r = answer.apply(uri, calls.size());
        if (r == null) {
            throw new IOException("연결 실패 " + uri);
        }
        return r;
    }

    static Response ok(String body) {
        return new Response(200, body);
    }

    static String query(URI uri, String name) {
        for (String p : uri.getRawQuery().split("&")) {
            if (p.startsWith(name + "=")) {
                return p.substring(name.length() + 1);
            }
        }
        return null;
    }
}
