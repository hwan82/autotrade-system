package com.autotrade.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDK {@code java.net.http} 구현. 연결 3초, 응답 본문까지 포함한 요청 전체 10초 제한(값은 {@code [가정]}: 알림이 늦어도 다음
 * 채널로 넘어가게 짧게 둔다). 기한을 넘기면 요청을 취소하고 {@link HttpTimeoutException}을 던진다. 헤더 뒤 본문이 멈춰도 같은
 * 기한에 끝난다(PR #127 architect F1). 리다이렉트는 따르지 않는다(Webhook URL이 다른 곳으로 새지 않게). 응답 본문은 앞 8KB만
 * 모으고 나머지는 받지 않는다.
 */
public final class JdkHttpTransport implements HttpTransport {

    static final int MAX_BODY_BYTES = 8 * 1024;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final Duration total;

    public JdkHttpTransport() {
        this(REQUEST_TIMEOUT);
    }

    JdkHttpTransport(Duration total) {
        this.total = Objects.requireNonNull(total, "total");
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public HttpResult post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException {
        Objects.requireNonNull(uri, "uri");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(total)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        CompletableFuture<HttpResponse<byte[]>> future =
                client.sendAsync(request.build(), info -> new CappedBody(MAX_BODY_BYTES));
        HttpResponse<byte[]> response;
        try {
            response = future.get(total.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("notification request timed out");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("notification request failed");
        }
        return new HttpResult(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    /** 본문을 앞 {@code cap}바이트까지만 모으고, 다 차면 구독을 끊어 나머지를 받지 않는다. */
    static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int cap;
        private final byte[] buffer;
        private int size;
        private Flow.Subscription subscription;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        CappedBody(int cap) {
            this.cap = cap;
            this.buffer = new byte[cap];
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer b : items) {
                int n = Math.min(b.remaining(), cap - size);
                b.get(buffer, size, n);
                size += n;
            }
            if (size == cap && !result.isDone()) {
                result.complete(java.util.Arrays.copyOf(buffer, size));
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(java.util.Arrays.copyOf(buffer, size));
        }
    }

    @Override
    public String toString() {
        return "JdkHttpTransport";
    }
}
