package com.autotrade.app.lab.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.lab.AutotradeLabApplication;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 모니터링 SSE를 연 채 실험실을 닫아도 graceful shutdown이 그 연결을 30초 기다리지 않는다(QA PR #114 R2, P3에서 30,045ms).
 * 실제 웹 서버를 띄워 실제 HTTP 연결로 잰다.
 */
class MonitorShutdownTest {

    @TempDir
    Path dir;

    @Test
    void 열린_실시간_연결이_실험실_종료를_늦추지_않는다() throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AutotradeLabApplication.class)
                .run("--server.port=0", "--autotrade.config-dir=" + dir);
        boolean closed = false;
        try {
            int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<InputStream> response = client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/monitor/stream"))
                            .header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(ctx.getBean(MonitorService.class).openStreams()).isEqualTo(1);

            long begin = System.nanoTime();
            ctx.close();
            closed = true;
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertThat(millis).as("종료 시간(ms)").isLessThan(10_000);
            response.body().close();
        } finally {
            if (!closed) {
                ctx.close();
            }
        }
    }
}
