package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkHttpTransportTest {

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void 본문과_헤더를_POST하고_응답_본문은_앞_8KB만_읽는다() throws Exception {
        AtomicReference<String> got = new AtomicReference<>();
        server.createContext("/big", ex -> {
            got.set(ex.getRequestMethod() + " " + ex.getRequestHeaders().getFirst("X-T") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] big = "a".repeat(100_000).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, big.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(big);
            }
        });
        HttpTransport.HttpResult r = new JdkHttpTransport().post(uri("/big"), Map.of("X-T", "v"), "안녕");
        assertThat(got.get()).isEqualTo("POST v 안녕");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).hasSize(JdkHttpTransport.MAX_BODY_BYTES);
    }

    @Test
    void 리다이렉트를_따르지_않는다() throws Exception {
        AtomicReference<Boolean> followed = new AtomicReference<>(false);
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", uri("/elsewhere").toString());
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        server.createContext("/elsewhere", ex -> {
            followed.set(true);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        HttpTransport.HttpResult r = new JdkHttpTransport().post(uri("/moved"), Map.of(), "x");
        assertThat(r.status()).isEqualTo(307);
        assertThat(followed.get()).isFalse();
    }

    @Test
    void 헤더_뒤_본문이_멈추면_기한_안에_시간_초과로_끝난다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/stall", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write("o".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> new JdkHttpTransport(Duration.ofSeconds(1)).post(uri("/stall"), Map.of(), "x"))
                    .isInstanceOf(HttpTimeoutException.class)
                    .hasMessageNotContaining("127.0.0.1");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
        }
    }

    @Test
    void 응답이_오지_않으면_기한_안에_시간_초과로_끝난다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/silent", ex -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> new JdkHttpTransport(Duration.ofSeconds(1)).post(uri("/silent"), Map.of(), "x"))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally {
            release.countDown();
        }
    }

    @Test
    void 본문이_아주_느리게_와도_기한_안에_끝난다() throws Exception {
        server.createContext("/drip", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 40; i++) {
                    out.write('o');
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (InterruptedException | java.io.IOException e) {
                // 클라이언트가 끊으면 여기로 온다
            }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> new JdkHttpTransport(Duration.ofSeconds(1)).post(uri("/drip"), Map.of(), "x"))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
