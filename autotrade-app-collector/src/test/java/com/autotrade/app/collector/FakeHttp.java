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
    /** POST 요청(주소, 본문). 한국투자 접근토큰 발급만 POST를 쓴다. */
    final List<URI> posts = new ArrayList<>();
    final List<String> postBodies = new ArrayList<>();
    private final BiFunction<URI, Integer, Response> answer;
    /** POST 답. 기본은 접근토큰 하나를 주는 응답. */
    java.util.function.Function<Integer, Response> postAnswer =
            n -> ok("{\"access_token\":\"TOKEN-" + n + "\",\"token_type\":\"Bearer\",\"expires_in\":86400}");

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

    @Override
    public Response post(URI uri, Map<String, String> h, String body) throws IOException {
        posts.add(uri);
        postBodies.add(body);
        Response r = postAnswer.apply(posts.size());
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
