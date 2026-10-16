package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
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
}
