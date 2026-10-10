package com.autotrade.broker.toss;

import com.autotrade.marketdata.live.HttpPort;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 테스트용 HTTP. 미리 넣은 응답을 순서대로 돌려주고 요청을 기록한다. */
final class FakeHttp implements HttpPort {

    record Call(String method, URI uri, Map<String, String> headers, String body) {}

    final List<Call> calls = new ArrayList<>();
    final Deque<Response> responses = new ArrayDeque<>();

    FakeHttp respond(int status, String body) {
        responses.add(new Response(status, body, Map.of()));
        return this;
    }

    FakeHttp respond(int status, String body, Map<String, String> headers) {
        responses.add(new Response(status, body, headers));
        return this;
    }

    @Override
    public Response post(URI uri, Map<String, String> headers, String body) {
        calls.add(new Call("POST", uri, headers, body));
        return responses.removeFirst();
    }

    @Override
    public Response get(URI uri, Map<String, String> headers) {
        calls.add(new Call("GET", uri, headers, ""));
        return responses.removeFirst();
    }
}
