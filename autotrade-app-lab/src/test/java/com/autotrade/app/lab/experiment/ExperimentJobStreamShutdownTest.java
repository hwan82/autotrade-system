package com.autotrade.app.lab.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.lab.AutotradeLabApplication;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * D-093: 실제 웹 서버에서 진행 상태 SSE가 첫 {@code jobs} 이벤트를 곧바로 보내고, 연결을 연 채 실험실을 닫아도 graceful
 * shutdown이 그 연결을 30초 기다리지 않는다(모니터링 SSE와 같은 기준, D-091).
 */
class ExperimentJobStreamShutdownTest {

    @TempDir
    Path dir;

    @Test
    void 첫_이벤트를_바로_보내고_열린_연결이_종료를_늦추지_않는다() throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AutotradeLabApplication.class)
                .run("--server.port=0", "--autotrade.config-dir=" + dir);
        boolean closed = false;
        try {
            int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<InputStream> response = client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/experiment-jobs/stream"))
                            .header("Accept", "text/event-stream").build(),
                    HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            assertThat(reader.readLine()).isEqualTo("event:jobs");
            assertThat(reader.readLine()).startsWith("data:").contains("\"items\":[]");
            assertThat(ctx.getBean(ExperimentJobStream.class).openStreams()).isEqualTo(1);

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
