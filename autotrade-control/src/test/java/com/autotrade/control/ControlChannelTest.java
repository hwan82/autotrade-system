package com.autotrade.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try") // 서버는 try 블록 동안 열어 두기만 한다
class ControlChannelTest {

    @TempDir
    Path dir;

    private final List<ControlRequest> received = new ArrayList<>();

    private ControlResponse echo(ControlRequest request) {
        received.add(request);
        return ControlResponse.accepted(request.command().name(),
                new EngineStatus(true, null, List.of(new EngineStatus.AccountStatus(
                        "KIS-1", "KIS", "KRX", "OFF", List.of(), true))), List.of());
    }

    private Path socket() {
        return dir.resolve("run").resolve("engine-control.sock");
    }

    @Test
    void 요청_하나에_응답_하나를_주고받는다() throws Exception {
        try (ControlServer server = ControlServer.start(socket(), this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            ControlResponse r = client.send(ControlRequest.activate("KIS-1", "KIS-1", "pw-placeholder"));

            assertThat(r.ok()).isTrue();
            assertThat(r.message()).isEqualTo("ACTIVATE");
            assertThat(r.status().accounts()).singleElement().satisfies(a -> assertThat(a.state()).isEqualTo("OFF"));
            assertThat(received).singleElement().satisfies(q -> {
                assertThat(q.account()).isEqualTo("KIS-1");
                assertThat(q.password()).isEqualTo("pw-placeholder");
            });
        }
    }

    @Test
    void 요청_문자열에는_비밀번호가_보이지_않는다() {
        ControlRequest request = ControlRequest.panicAll(ControlRequest.PANIC_ALL_PHRASE, "pw-placeholder");
        assertThat(request.toString()).doesNotContain("pw-placeholder").contains("****");
        assertThat(ControlRequest.status().toString()).doesNotContain("****");
    }

    @Test
    void 형식이_틀리거나_모르는_필드가_있으면_처리기를_부르지_않는다() throws Exception {
        try (ControlServer server = ControlServer.start(socket(), this::echo)) {
            String bad = raw("{\"command\":\"ACTIVATE\",\"password\":\"pw-placeholder\",\"extra\":1}\n");
            String unknown = raw("{\"command\":\"LAUNCH\"}\n");
            String notJson = raw("pw-placeholder\n");

            for (String response : List.of(bad, unknown, notJson)) {
                assertThat(response).contains("BAD_REQUEST").doesNotContain("pw-placeholder");
            }
            assertThat(received).isEmpty();
        }
    }

    @Test
    void 너무_큰_요청은_거절한다() throws Exception {
        try (ControlServer server = ControlServer.start(socket(), this::echo)) {
            String huge = "{\"command\":\"STATUS\",\"reason\":\"" + "a".repeat(ControlCodec.MAX_BYTES) + "\"}\n";
            assertThat(raw(huge)).contains("BAD_REQUEST");
            assertThat(received).isEmpty();
        }
    }

    @Test
    void 요청을_보내지_않는_연결은_시간_초과로_닫힌다() throws Exception {
        try (ControlServer server = ControlServer.start(socket(), this::echo);
                SocketChannel idle = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            idle.connect(UnixDomainSocketAddress.of(socket()));
            ByteBuffer buf = ByteBuffer.allocate(16);
            long start = System.nanoTime();
            int n;
            try {
                n = idle.read(buf);
            } catch (IOException e) {
                n = -1;
            }
            assertThat(n).isEqualTo(-1);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(ControlServer.READ_TIMEOUT.plusSeconds(5));
        }
    }

    @Test
    void 처리기_예외는_내부_오류로_돌려주고_서버는_계속_돈다() throws Exception {
        try (ControlServer server = ControlServer.start(socket(), r -> {
                    throw new IllegalStateException("secret detail");
                });
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            ControlResponse r = client.send(ControlRequest.status());
            assertThat(r.code()).isEqualTo(ControlCodes.INTERNAL);
            assertThat(r.message()).doesNotContain("secret detail");
            assertThat(client.send(ControlRequest.status()).code()).isEqualTo(ControlCodes.INTERNAL);
        }
    }

    @Test
    void 명령은_한_번에_하나씩_처리한다() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int[] concurrent = {0};
        int[] max = {0};
        try (ControlServer server = ControlServer.start(socket(), r -> {
                    synchronized (max) {
                        concurrent[0]++;
                        max[0] = Math.max(max[0], concurrent[0]);
                    }
                    inside.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    synchronized (max) {
                        concurrent[0]--;
                    }
                    return ControlResponse.accepted("", null, List.of());
                });
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(10))) {
            Thread a = Thread.ofVirtual().start(() -> sendQuietly(client));
            Thread b = Thread.ofVirtual().start(() -> sendQuietly(client));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            release.countDown();
            a.join(10_000);
            b.join(10_000);
            assertThat(max[0]).isEqualTo(1);
        }
    }

    @Test
    void 엔진이_없으면_연결_불가를_알린다() {
        try (ControlClient client = new ControlClient(socket(), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOf(ControlClient.EngineUnreachableException.class);
        }
    }

    @Test
    void 응답하지_않는_엔진은_시간_초과로_알린다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ControlServer server = ControlServer.start(socket(), r -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ControlResponse.accepted("", null, List.of());
                });
                ControlClient client = new ControlClient(socket(), Duration.ofMillis(300))) {
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOf(ControlClient.EngineUnreachableException.class)
                    .hasMessageContaining("응답");
            release.countDown();
        }
    }

    @Test
    void 죽은_엔진의_소켓은_지우고_살아_있는_엔진이_있으면_시작하지_않는다() throws Exception {
        try (ControlServer first = ControlServer.start(socket(), this::echo)) {
            assertThatThrownBy(() -> ControlServer.start(socket(), this::echo))
                    .isInstanceOf(IOException.class).hasMessageContaining("이미");
        }
        Files.createDirectories(socket().getParent());
        Files.writeString(socket(), "stale");
        try (ControlServer second = ControlServer.start(socket(), this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            assertThat(client.send(ControlRequest.status()).ok()).isTrue();
        }
    }

    @Test
    void POSIX에서는_소켓_폴더와_파일을_소유자만_쓸_수_있다() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try (ControlServer server = ControlServer.start(socket(), this::echo)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket().getParent())))
                    .isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket())))
                    .isEqualTo("rw-------");
        }
    }

    private static void sendQuietly(ControlClient client) {
        try {
            client.send(ControlRequest.status());
        } catch (ControlClient.EngineUnreachableException e) {
            throw new IllegalStateException(e);
        }
    }

    private String raw(String line) throws IOException {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket()));
            ByteBuffer out = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
            while (out.hasRemaining()) {
                ch.write(out);
            }
            ByteBuffer in = ByteBuffer.allocate(8192);
            StringBuilder sb = new StringBuilder();
            while (ch.read(in) > 0) {
                in.flip();
                sb.append(StandardCharsets.UTF_8.decode(in));
                in.clear();
                if (sb.indexOf("\n") >= 0) {
                    break;
                }
            }
            return sb.toString();
        }
    }
}
