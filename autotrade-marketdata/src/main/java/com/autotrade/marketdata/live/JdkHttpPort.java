package com.autotrade.marketdata.live;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** JDK {@link HttpClient} 구현. 응답 헤더는 소문자 이름으로 첫 값만 담는다. */
public final class JdkHttpPort implements HttpPort {

    private final HttpClient client;
    private final Duration timeout;

    public JdkHttpPort(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public Response post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return send(b.build());
    }

    @Override
    public Response get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach(b::header);
        return send(b.build());
    }

    private Response send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, String> h = new TreeMap<>();
        r.headers().map().forEach((k, v) -> {
            if (!v.isEmpty()) {
                h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0));
            }
        });
        return new Response(r.statusCode(), r.body(), h);
    }
}
