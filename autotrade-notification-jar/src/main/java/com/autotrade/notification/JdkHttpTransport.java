package com.autotrade.notification;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * JDK {@code java.net.http} 구현. 연결 3초, 요청 전체 10초 제한(값은 {@code [가정]}: 알림이 늦어도 다음 채널로 넘어가게
 * 짧게 둔다). 리다이렉트는 따르지 않는다(Webhook URL이 다른 곳으로 새지 않게). 응답 본문은 앞 8KB만 읽는다.
 */
public final class JdkHttpTransport implements HttpTransport {

    static final int MAX_BODY_BYTES = 8 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public JdkHttpTransport() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public HttpResult post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException {
        Objects.requireNonNull(uri, "uri");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] head = in.readNBytes(MAX_BODY_BYTES);
            return new HttpResult(response.statusCode(), new String(head, StandardCharsets.UTF_8));
        }
    }

    @Override
    public String toString() {
        return "JdkHttpTransport";
    }
}
