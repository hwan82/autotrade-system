package com.autotrade.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try") // 서버는 try 블록 동안 열어 두기만 한다
@Timeout(30)
class ControlChannelTest {

    @TempDir
    Path dir;

    private final List<ControlRequest> received = new CopyOnWriteArrayList<>();

    private ControlResponse echo(ControlRequest request) {
        received.add(request);
        return ControlResponse.accepted(request.command().name(),
                new EngineStatus(true, null, List.of(new EngineStatus.AccountStatus(
                        "KIS-1", "KIS", "KRX", "OFF", List.of(), true))), List.of());
    }

    /** 자가 확인을 끈 서버(시험이 checkIntegrity를 직접 부른다). */
    private ControlServer start(java.util.function.Function<ControlRequest, ControlResponse> handler) throws IOException {
        return ControlServer.start(socket(), (r, receivedAt) -> handler.apply(r), ControlServer.Listener.NONE, Duration.ZERO);
    }

    private Path socket() {
        return dir.resolve("run").resolve("engine-control.sock");
    }

    @Test
    void 요청_하나에_응답_하나를_주고받는다() throws Exception {
        try (ControlServer server = start(this::echo);
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
    void 요청_문자열에는_비밀번호와_계좌_칸이_보이지_않는다() {
        ControlRequest request = ControlRequest.panicAccount("pasted-value", "KIS-1", "pw-placeholder");
        assertThat(request.toString()).doesNotContain("pw-placeholder").doesNotContain("pasted-value").contains("****");
        assertThat(ControlRequest.status().toString()).doesNotContain("****");
    }

    @Test
    void 형식이_틀리거나_모르는_필드가_있으면_처리기를_부르지_않는다() throws Exception {
        try (ControlServer server = start(this::echo)) {
            List<String> responses = List.of(
                    raw("{\"command\":\"ACTIVATE\",\"password\":\"pw-placeholder\",\"extra\":1}\n"),
                    raw("{\"command\":\"LAUNCH\"}\n"),
                    raw("pw-placeholder\n"),
                    raw("null\n"),
                    raw("{\"command\":\"STATUS\",\"command\":\"ACTIVATE\"}\n"),
                    raw("{\"command\":1}\n"),
                    raw("{\"command\":\"ACTIVATE\",\"account\":12}\n"),
                    raw("{\"command\":\"STATUS\"} {\"command\":\"ACTIVATE\"}\n"),
                    raw("{\"command\":\"STATUS\"}\n{\"command\":\"ACTIVATE\"}\n"));

            assertThat(responses).allSatisfy(response ->
                    assertThat(response).contains("BAD_REQUEST").doesNotContain("pw-placeholder"));
            assertThat(received).isEmpty();
        }
    }

    @Test
    void 메시지_크기는_줄바꿈을_포함해_MAX_BYTES까지_받는다() throws Exception {
        try (ControlServer server = start(this::echo)) {
            assertThat(raw(sized(ControlCodec.MAX_BYTES - 1) + "\n")).contains("\"code\":\"OK\"");
            assertThat(raw(sized(ControlCodec.MAX_BYTES) + "\n")).contains("BAD_REQUEST");
            assertThat(received).hasSize(1);
        }
    }

    /** 정확히 {@code bytes} 바이트인 STATUS 요청 본문. */
    private static String sized(int bytes) {
        String head = "{\"command\":\"STATUS\",\"reason\":\"";
        String tail = "\"}";
        String body = head + "a".repeat(bytes - head.length() - tail.length()) + tail;
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
        return body;
    }

    @Test
    void 요청을_보내지_않는_연결은_시간_초과로_닫힌다() throws Exception {
        try (ControlServer server = start(this::echo);
                SocketChannel idle = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            idle.connect(UnixDomainSocketAddress.of(socket()));
            long start = System.nanoTime();

            assertThat(readUntilClosed(idle)).isEqualTo(-1);
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            assertThat(took).isGreaterThan(ControlServer.READ_TIMEOUT.minusSeconds(1))
                    .isLessThan(ControlServer.READ_TIMEOUT.plusSeconds(5));
        }
    }

    @Test
    void 동시_연결_상한을_넘으면_닫고_자리가_나면_다시_받는다() throws Exception {
        List<SocketChannel> idle = new ArrayList<>();
        try (ControlServer server = watched(this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            for (int i = 0; i < ControlServer.MAX_CONNECTIONS; i++) {
                SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                ch.connect(UnixDomainSocketAddress.of(socket()));
                idle.add(ch);
            }
            Thread.sleep(200);
            try (SocketChannel extra = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                extra.connect(UnixDomainSocketAddress.of(socket()));
                long start = System.nanoTime();
                assertThat(readUntilClosed(extra)).isEqualTo(-1);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            }
            // 상한 도달은 엔진이 긴급 알림으로 바꾸도록 알린다(C5, 1분에 한 번). 알림은 연결을 닫은 뒤에 간다
            for (int i = 0; i < 100 && events.isEmpty(); i++) {
                Thread.sleep(20);
            }
            assertThat(events).singleElement().asString().startsWith("CONNECTIONS_SATURATED");
            for (SocketChannel ch : idle) {
                ch.close();
            }
            Thread.sleep(200);
            assertThat(client.send(ControlRequest.status()).ok()).isTrue();
        } finally {
            for (SocketChannel ch : idle) {
                ch.close();
            }
        }
    }

    @Test
    void 처리기_예외는_내부_오류로_돌려주고_서버는_계속_돈다() throws Exception {
        try (ControlServer server = start(r -> {
                    throw new IllegalStateException("secret detail");
                });
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            ControlResponse r = client.send(ControlRequest.status());
            assertThat(r.code()).isEqualTo(ControlCodes.INTERNAL);
            assertThat(r.message()).doesNotContain("secret detail");
            assertThat(client.send(ControlRequest.panicAll("x", "y")).code()).isEqualTo(ControlCodes.INTERNAL);
        }
    }

    @Test
    void 명령은_서로_기다리지_않고_연결마다_바로_처리기에_간다() throws Exception {
        // 명령끼리의 순서·계좌별 처리·기한은 처리기(EngineCommandHandler)가 정한다(D-142). 서버는 긴 명령 중에도 다른 명령을 바로 넘긴다
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        try (ControlServer server = start(r -> {
                    if (r.command() != ControlCommand.STATUS && r.command() != ControlCommand.DEACTIVATE) {
                        max.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                        inside.countDown();
                        await(release);
                        concurrent.decrementAndGet();
                    }
                    return echo(r);
                });
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(10))) {
            List<ControlResponse> responses = new CopyOnWriteArrayList<>();
            Thread a = Thread.ofVirtual().start(() -> responses.add(sendQuietly(client, ControlRequest.panicAll("x", "y"))));
            Thread b = Thread.ofVirtual().start(() ->
                    responses.add(sendQuietly(client, ControlRequest.activate("KIS-1", "KIS-1", "y"))));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(client.send(ControlRequest.status(), Duration.ofSeconds(2)).message()).isEqualTo("STATUS");
            assertThat(client.send(ControlRequest.deactivate("KIS-1", null), Duration.ofSeconds(2)).message())
                    .isEqualTo("DEACTIVATE");
            release.countDown();
            a.join(10_000);
            b.join(10_000);

            assertThat(max).hasValue(2);
            assertThat(responses).hasSize(2).allSatisfy(r -> assertThat(r.ok()).isTrue());
        }
    }

    @Test
    void 처리기는_연결을_받은_시각을_받는다() throws Exception {
        List<Long> times = new CopyOnWriteArrayList<>();
        try (ControlServer server = ControlServer.start(socket(), (r, receivedAt) -> {
                    times.add(System.nanoTime() - receivedAt);
                    return echo(r);
                }, ControlServer.Listener.NONE, Duration.ZERO);
                SocketChannel slow = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            slow.connect(UnixDomainSocketAddress.of(socket()));
            Thread.sleep(400);
            ByteBuffer out = ByteBuffer.wrap("{\"command\":\"STATUS\"}\n".getBytes(StandardCharsets.UTF_8));
            while (out.hasRemaining()) {
                slow.write(out);
            }
            readLine(slow);
            // 요청을 늦게 보내도 기한은 연결을 받은 때부터 잰다(요청 읽기 시간이 기한 안에 든다)
            assertThat(times).singleElement().satisfies(t -> assertThat(t).isGreaterThanOrEqualTo(Duration.ofMillis(350).toNanos()));
        }
    }

    @Test
    void 엔진이_없으면_실행_중이_아님을_알린다() throws Exception {
        try (ControlClient client = new ControlClient(socket(), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class,
                            e -> assertThat(e.reach()).isEqualTo(ControlClient.Reach.NOT_RUNNING));
        }
        // 비정상 종료로 소켓 파일과 잠금 파일만 남은 경우도 실행 중이 아니다(잠금 파일의 PID가 죽음)
        deadSocket();
        Process finished = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        finished.waitFor();
        Files.writeString(socket().getParent().resolve(ControlServer.LOCK_FILE), Long.toString(finished.pid()));
        try (ControlClient client = new ControlClient(socket(), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class,
                            e -> assertThat(e.reach()).isEqualTo(ControlClient.Reach.NOT_RUNNING));
        }
    }

    @Test
    void 잠금_파일을_읽지_못하거나_내용이_PID가_아니면_알_수_없음이다() throws Exception {
        deadSocket();
        Path lock = socket().getParent().resolve(ControlServer.LOCK_FILE);
        Files.writeString(lock, "");
        try (ControlClient client = new ControlClient(socket(), Duration.ofSeconds(1))) {
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class,
                            e -> assertThat(e.reach()).isEqualTo(ControlClient.Reach.UNKNOWN));
            // 읽기 오류(Windows 강제 잠금 등)를 폴더로 흉내 낸다
            Files.delete(lock);
            Files.createDirectory(lock);
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class,
                            e -> assertThat(e.reach()).isEqualTo(ControlClient.Reach.UNKNOWN));
        }
    }

    @Test
    void 엔진이_살아_있는데_소켓만_없어지면_실행_중이_아님이_아니라_알_수_없음이다() throws Exception {
        try (ControlServer server = start(this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(1))) {
            assertThat(Files.readString(socket().getParent().resolve(ControlServer.LOCK_FILE)))
                    .isEqualTo(Long.toString(ProcessHandle.current().pid()));
            Files.delete(socket());

            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class,
                            e -> assertThat(e.reach()).isEqualTo(ControlClient.Reach.UNKNOWN));
        }
    }

    @Test
    void 응답하지_않는_엔진은_상태를_알_수_없음으로_알린다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ControlServer server = start(r -> {
                    await(release);
                    return ControlResponse.accepted("", null, List.of());
                });
                ControlClient client = new ControlClient(socket(), Duration.ofMillis(300))) {
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class, e -> {
                        assertThat(e.reach()).isEqualTo(ControlClient.Reach.UNKNOWN);
                        assertThat(e.getMessage()).contains("응답");
                    });
            release.countDown();
        }
    }

    @Test
    void 응답의_모르는_필드는_무시한다() throws Exception {
        Files.createDirectories(socket().getParent());
        try (ServerSocketChannel fake = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            fake.bind(UnixDomainSocketAddress.of(socket()));
            Thread engine = Thread.ofVirtual().start(() -> {
                try (SocketChannel ch = fake.accept()) {
                    readLine(ch);
                    ch.write(ByteBuffer.wrap(("{\"ok\":true,\"code\":\"OK\",\"message\":\"m\",\"status\":null,"
                            + "\"steps\":[],\"newField\":1}\n").getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            try (ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
                assertThat(client.send(ControlRequest.status()).message()).isEqualTo("m");
            }
            engine.join(5_000);
        }
    }

    @Test
    void 설정_경고가_없는_옛_엔진의_상태는_빈_경고로_읽는다() throws Exception {
        Files.createDirectories(socket().getParent());
        try (ServerSocketChannel fake = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            fake.bind(UnixDomainSocketAddress.of(socket()));
            Thread engine = Thread.ofVirtual().start(() -> {
                try (SocketChannel ch = fake.accept()) {
                    readLine(ch);
                    ch.write(ByteBuffer.wrap(("{\"ok\":true,\"code\":\"OK\",\"message\":\"\",\"status\":"
                            + "{\"passwordConfigured\":true,\"lockedUntil\":null,\"accounts\":[]},\"steps\":[]}\n")
                            .getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            try (ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
                assertThat(client.send(ControlRequest.status()).status().configWarning()).isEmpty();
            }
            engine.join(5_000);
        }
    }

    @Test
    void 설정_경고는_상태와_함께_전달된다() throws Exception {
        EngineStatus warned = new EngineStatus(false, null, List.of(), "runtime-config.json을(를) 쓰지 않고 default로 기동: x");
        try (ControlServer server = start(r -> ControlResponse.accepted("", warned, List.of()));
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            assertThat(client.send(ControlRequest.status()).status()).isEqualTo(warned);
        }
    }

    @Test
    void 긴_설정_경고는_잘라서_응답_한도_안에_둔다() throws Exception {
        String huge = "가".repeat(100_000);
        EngineStatus warned = new EngineStatus(false, null, List.of(), huge);
        assertThat(warned.configWarning()).hasSize(EngineStatus.MAX_CONFIG_WARNING + EngineStatus.TRUNCATED_MARK.length())
                .endsWith(EngineStatus.TRUNCATED_MARK);
        // 서로게이트 쌍 가운데서 자르지 않는다
        String emoji = "a" + "😀".repeat(EngineStatus.MAX_CONFIG_WARNING);
        assertThat(new EngineStatus(false, null, List.of(), emoji).configWarning())
                .isEqualTo("a" + "😀".repeat((EngineStatus.MAX_CONFIG_WARNING - 1) / 2) + EngineStatus.TRUNCATED_MARK);
        try (ControlServer server = start(r -> ControlResponse.accepted("", warned, List.of()));
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            assertThat(client.send(ControlRequest.status()).status().configWarning()).isEqualTo(warned.configWarning());
        }
    }

    @Test
    void 같은_폴더에_엔진이_떠_있으면_소켓을_지워도_시작하지_않는다() throws Exception {
        try (ControlServer first = start(this::echo)) {
            assertThatThrownBy(() -> start(this::echo))
                    .isInstanceOf(IOException.class).hasMessageContaining("이미 실행 중");
            Files.delete(socket());
            assertThatThrownBy(() -> start(this::echo))
                    .isInstanceOf(IOException.class).hasMessageContaining("이미 실행 중");
        }
        try (ControlServer again = start(this::echo)) {
            assertThat(socket()).exists();
        }
    }

    @Test
    void 죽은_엔진의_소켓은_지우고_시작한다() throws Exception {
        deadSocket();
        assertThat(socket()).exists();
        try (ControlServer server = start(this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(5))) {
            assertThat(client.send(ControlRequest.status()).ok()).isTrue();
        }
    }

    @Test
    void 소켓_폴더가_심볼릭_링크면_시작하지_않는다() throws Exception {
        Path real = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createSymbolicLink(dir.resolve("run"), real);

        assertThatThrownBy(() -> start(this::echo))
                .isInstanceOf(IOException.class).hasMessageContaining("심볼릭 링크");
        assertThat(real.resolve("engine-control.sock")).doesNotExist();
    }

    @Test
    void POSIX에서는_소켓_폴더와_파일을_소유자만_쓸_수_있다() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try (ControlServer server = start(this::echo)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket().getParent())))
                    .isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket())))
                    .isEqualTo("rw-------");
        }
    }

    // ---- 자가 확인(C6)·잠금 파일(C8)·시험 연결(C9)·닫기(C7)·응답 멈춤 ----

    private final List<String> events = new CopyOnWriteArrayList<>();

    private ControlServer watched(java.util.function.Function<ControlRequest, ControlResponse> handler) throws IOException {
        return ControlServer.start(socket(), (r, receivedAt) -> handler.apply(r),
                (event, detail) -> events.add(event + " " + detail), Duration.ZERO);
    }

    @Test
    void 지워진_소켓은_자가_확인으로_다시_만들어_명령이_이_엔진에_다시_닿는다() throws Exception {
        try (ControlServer server = watched(this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(2))) {
            Files.delete(socket());
            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class,
                            e -> assertThat(e.reach()).isEqualTo(ControlClient.Reach.UNKNOWN));

            server.checkIntegrity();

            assertThat(client.send(ControlRequest.status()).message()).isEqualTo("STATUS");
            assertThat(events).singleElement().asString().startsWith("SOCKET_RECREATED");
            server.checkIntegrity();
            assertThat(events).hasSize(1);
        }
    }

    @Test
    void run_폴더를_통째로_지우면_폴더_잠금_소켓을_다시_만들어_두_번째_서버가_뜨지_못한다() throws Exception {
        try (ControlServer server = watched(this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(2))) {
            Path run = socket().getParent();
            Files.delete(socket());
            Files.delete(run.resolve(ControlServer.LOCK_FILE));
            Files.delete(run);

            server.checkIntegrity();

            assertThat(events).extracting(e -> e.split(" ")[0])
                    .containsExactly("RUN_DIR_RECREATED", "LOCK_RECREATED", "SOCKET_RECREATED");
            assertThat(Files.readString(run.resolve(ControlServer.LOCK_FILE)))
                    .isEqualTo(Long.toString(ProcessHandle.current().pid()));
            assertThat(client.send(ControlRequest.status()).ok()).isTrue();
            // 다시 만든 잠금을 이 엔진이 쥐고 있으므로 두 번째 서버는 뜨지 않는다
            assertThatThrownBy(() -> start(this::echo))
                    .isInstanceOf(IOException.class).hasMessageContaining("이미 실행 중");
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(run))).isEqualTo("rwx------");
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(socket()))).isEqualTo("rw-------");
            }
        }
    }

    @Test
    void 지운_뒤_다른_프로세스가_잡은_잠금과_소켓은_빼앗지_않고_알린다() throws Exception {
        Path run = socket().getParent();
        try (ControlServer server = watched(this::echo)) {
            Files.delete(socket());
            Files.delete(run.resolve(ControlServer.LOCK_FILE));
            // 두 번째 엔진 흉내: 새 잠금 파일을 잡고 새 소켓에서 듣는다
            try (java.nio.channels.FileChannel other = java.nio.channels.FileChannel.open(run.resolve(ControlServer.LOCK_FILE),
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                    java.nio.channels.FileLock held = other.lock();
                    ServerSocketChannel second = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                second.bind(UnixDomainSocketAddress.of(socket()));

                server.checkIntegrity();
                server.checkIntegrity();

                assertThat(events).extracting(e -> e.split(" ")[0]).containsExactly("LOCK_TAKEN", "SOCKET_TAKEN");
                assertThat(socket()).exists();
                server.close();
                // 닫을 때 다른 프로세스의 소켓 파일을 지우지 않는다
                assertThat(socket()).exists();
            }
        }
    }

    @Test
    void 응답을_멈춘_서버는_어떤_요청에도_답하지_않는다() throws Exception {
        try (ControlServer server = watched(this::echo);
                ControlClient client = new ControlClient(socket(), Duration.ofSeconds(2))) {
            assertThat(client.send(ControlRequest.status()).ok()).isTrue();
            server.silence("시험");
            received.clear();

            assertThatThrownBy(() -> client.send(ControlRequest.status()))
                    .isInstanceOfSatisfying(ControlClient.EngineUnreachableException.class,
                            e -> assertThat(e.reach()).isEqualTo(ControlClient.Reach.UNKNOWN));
            assertThatThrownBy(() -> client.send(ControlRequest.deactivate("KIS-1", null)))
                    .isInstanceOf(ControlClient.EngineUnreachableException.class);
            assertThat(received).isEmpty();
            assertThat(server.silenced()).isTrue();
        }
    }

    @Test
    void 잠금_파일이_심볼릭_링크면_따라가지_않고_시작하지_않는다() throws Exception {
        Path run = Files.createDirectories(socket().getParent());
        Path target = Files.writeString(dir.resolve("victim.txt"), "keep");
        Files.createSymbolicLink(run.resolve(ControlServer.LOCK_FILE), target);

        assertThatThrownBy(() -> start(this::echo)).isInstanceOf(IOException.class);
        assertThat(Files.readString(target)).isEqualTo("keep");
    }

    @Test
    void 잠금_파일은_PID만_담는다() throws Exception {
        Path lock = Files.createDirectories(socket().getParent()).resolve(ControlServer.LOCK_FILE);
        Files.writeString(lock, "99999999999999999999 이전 내용");
        try (ControlServer server = start(this::echo)) {
            assertThat(Files.readString(lock)).isEqualTo(Long.toString(ProcessHandle.current().pid()));
        }
    }

    @Test
    void 기존_엔진_확인용_시험_연결은_상한_안에_끝난다() throws Exception {
        Path busy = dir.resolve("busy.sock");
        try (ServerSocketChannel stuck = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            stuck.bind(UnixDomainSocketAddress.of(busy), 1);
            List<Thread> fillers = new ArrayList<>();
            // 받지 않는 서버의 접속 대기열을 채워 다음 연결이 멈추게 한다(멈춘 연결은 서버를 닫으면 풀린다)
            for (int i = 0; i < 6; i++) {
                fillers.add(Thread.ofVirtual().start(() -> {
                    try {
                        SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX);
                        c.connect(UnixDomainSocketAddress.of(busy));
                    } catch (IOException ignored) {
                        // 서버가 닫히면 끝난다
                    }
                }));
            }
            Thread.sleep(300);
            long started = System.nanoTime();
            boolean listening = ControlServer.isListening(busy);
            long took = System.nanoTime() - started;

            assertThat(listening).isTrue();
            assertThat(Duration.ofNanos(took)).isLessThan(ControlServer.PROBE_TIMEOUT.plusSeconds(2));
        }
        assertThat(ControlServer.isListening(dir.resolve("none.sock"))).isFalse();
    }

    @Test
    void 닫을_때_진행_중인_명령은_응답을_마친다() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        List<ControlResponse> responses = new CopyOnWriteArrayList<>();
        ControlServer server = start(r -> {
            if (r.command() == ControlCommand.PANIC_ALL) {
                inside.countDown();
                sleep(500);
            }
            return echo(r);
        });
        try (ControlClient client = new ControlClient(socket(), Duration.ofSeconds(10))) {
            Thread t = Thread.ofVirtual().start(() -> responses.add(sendQuietly(client, ControlRequest.panicAll("x", "y"))));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            server.close();
            t.join(10_000);
        }
        assertThat(responses).singleElement().satisfies(r -> assertThat(r.message()).isEqualTo("PANIC_ALL"));
        assertThat(socket()).doesNotExist();
    }

    /** 바인드했다가 닫아 듣는 쪽 없이 파일만 남은 소켓을 만든다. */
    private void deadSocket() throws IOException {
        Files.createDirectories(socket().getParent());
        try (ServerSocketChannel dead = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            dead.bind(UnixDomainSocketAddress.of(socket()));
        }
    }

    private static ControlResponse sendQuietly(ControlClient client, ControlRequest request) {
        try {
            return client.send(request);
        } catch (ControlClient.EngineUnreachableException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int readUntilClosed(SocketChannel ch) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        try {
            int n;
            do {
                buf.clear();
                n = ch.read(buf);
            } while (n > 0);
            return n;
        } catch (IOException e) {
            return -1;
        }
    }

    private static void readLine(SocketChannel ch) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        while (ch.read(one) > 0) {
            if (one.get(0) == '\n') {
                return;
            }
            one.clear();
        }
    }

    private String raw(String line) throws IOException {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket()));
            ByteBuffer out = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
            try {
                while (out.hasRemaining()) {
                    ch.write(out);
                }
            } catch (IOException e) {
                // 서버가 크기 초과로 먼저 답하고 닫을 수 있다
            }
            ByteBuffer in = ByteBuffer.allocate(8192);
            StringBuilder sb = new StringBuilder();
            try {
                while (ch.read(in) > 0) {
                    in.flip();
                    sb.append(StandardCharsets.UTF_8.decode(in));
                    in.clear();
                    if (sb.indexOf("\n") >= 0) {
                        break;
                    }
                }
            } catch (IOException e) {
                // 응답 뒤 연결이 끊김
            }
            return sb.toString();
        }
    }
}
