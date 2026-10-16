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

    /**
     * 기한을 넘기면 요청을 취소해 연결을 끊는다(PR #127 QA Q1, 변이 M2). 헤더가 온 뒤라 요청 자체의 시간 제한은 걸리지 않으므로,
     * 취소하지 않으면 서버는 계속 쓸 수 있다.
     */
    @Test
    void 기한을_넘기면_요청을_취소해_서버_쪽_연결이_끊긴다() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Long> brokeAt = new AtomicReference<>();
        server.createContext("/trickle", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            try {
                for (int i = 0; i < 100; i++) {
                    out.write('o');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (java.io.IOException e) {
                brokeAt.set(System.nanoTime());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
                ex.close();
            }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> new JdkHttpTransport(Duration.ofSeconds(1)).post(uri("/trickle"), Map.of(), "x"))
                .isInstanceOf(HttpTimeoutException.class);
        assertThat(done.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(brokeAt.get()).as("클라이언트가 연결을 끊어 서버 쓰기가 실패해야 한다").isNotNull();
        assertThat(Duration.ofNanos(brokeAt.get() - start)).isLessThan(Duration.ofSeconds(5));
    }

    /** 8KB를 채우면 구독을 끊어 나머지를 받지 않는다(PR #127 QA Q1, 변이 M8). 끊지 않으면 서버는 큰 본문을 끝까지 다 보낸다. */
    @Test
    void 본문_상한을_채우면_나머지를_받지_않고_연결을_끊는다() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> broke = new AtomicReference<>(false);
        server.createContext("/flood", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'a');
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 1024; i++) { // 64MB
                    out.write(chunk);
                }
            } catch (java.io.IOException e) {
                broke.set(true);
            } finally {
                done.countDown();
            }
        });
        HttpTransport.HttpResult r = new JdkHttpTransport(Duration.ofSeconds(5)).post(uri("/flood"), Map.of(), "x");
        assertThat(r.body()).hasSize(JdkHttpTransport.MAX_BODY_BYTES);
        assertThat(done.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(broke.get()).as("상한 뒤 구독을 끊어 서버가 64MB를 다 보내지 못해야 한다").isTrue();
    }
}
