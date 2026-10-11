package com.autotrade.app.collector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** JDK {@link HttpClient} 구현. 연결 10초, 응답 60초 제한. */
final class JdkHttpGetter implements HttpGetter {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER) // 리다이렉트로 키 헤더가 다른 호스트에 가지 않게, 3xx는 오류
            .build();

    @Override
    public Response get(URI uri, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET();
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("요청이 중단되었습니다", e);
        }
    }
}
