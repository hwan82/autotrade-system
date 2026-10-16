package com.autotrade.notification;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 미리 넣은 응답을 차례로 돌려주고 요청을 기록하는 가짜 HTTP. */
final class FakeHttp implements HttpTransport {

    record Request(URI uri, Map<String, String> headers, String body) {}

    interface Reply {
        HttpResult get() throws IOException, InterruptedException;
    }

    final List<Request> requests = new ArrayList<>();
    private final Deque<Reply> replies = new ArrayDeque<>();

    FakeHttp reply(int status, String body) {
        replies.add(() -> new HttpResult(status, body));
        return this;
    }

    FakeHttp fail(IOException e) {
        replies.add(() -> {
            throw e;
        });
        return this;
    }

    FakeHttp interrupt() {
        replies.add(() -> {
            throw new InterruptedException();
        });
        return this;
    }

    @Override
    public HttpResult post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException {
        requests.add(new Request(uri, headers, body));
        Reply r = replies.poll();
        if (r == null) {
            throw new AssertionError("예상하지 않은 요청: " + uri);
        }
        return r.get();
    }
}
