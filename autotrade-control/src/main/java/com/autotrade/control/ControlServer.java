package com.autotrade.control;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 실전 엔진 쪽 명령 채널 서버(ADR-006 2.2). Unix 도메인 소켓 하나를 열고 요청마다 처리기를 부른다.
 *
 * <ul>
 *   <li>명령은 한 번에 하나씩 처리한다(단일 스레드). 연결 읽기는 따로 하므로 느린 연결이 명령 처리를 막지 않는다.</li>
 *   <li>동시 연결은 {@link #MAX_CONNECTIONS}개까지, 요청 한 줄을 {@link #READ_TIMEOUT} 안에 보내야 한다.</li>
 *   <li>POSIX에서는 소켓 폴더를 소유자 전용(700)으로 만든다. 이미 다른 엔진이 소켓을 쓰고 있으면 시작하지 않는다.</li>
 *   <li>서버 스레드는 모두 데몬이다. 명령 채널이 죽어도 엔진의 보유 종목 보호는 계속된다(UI-06).</li>
 * </ul>
 */
public final class ControlServer implements AutoCloseable {

    public static final int MAX_CONNECTIONS = 4;
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger LOG = System.getLogger(ControlServer.class.getName());

    private final Path socket;
    private final Function<ControlRequest, ControlResponse> handler;
    private final ServerSocketChannel server;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(daemon("control-command"));
    private final ExecutorService connections = Executors.newThreadPerTaskExecutor(daemon("control-conn"));
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(daemon("control-timeout"));
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Thread acceptor;

    private ControlServer(Path socket, Function<ControlRequest, ControlResponse> handler) throws IOException {
        this.socket = socket;
        this.handler = handler;
        prepareDirectory(socket.getParent());
        removeStaleSocket(socket);
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(socket));
        } catch (IOException e) {
            server.close();
            throw new IOException("명령 채널 소켓을 열지 못했습니다(" + e.getMessage()
                    + "). 소켓 경로가 길면(약 100자 초과) AUTOTRADE_CONFIG_DIR을 짧은 경로로 바꾸세요", e);
        }
        restrictSocketFile(socket);
        this.acceptor = daemon("control-accept").newThread(this::acceptLoop);
        acceptor.start();
    }

    /** 소켓을 열고 요청을 받기 시작한다. */
    public static ControlServer start(Path socket, Function<ControlRequest, ControlResponse> handler)
            throws IOException {
        return new ControlServer(Objects.requireNonNull(socket, "socket"), Objects.requireNonNull(handler, "handler"));
    }

    public Path socket() {
        return socket;
    }

    private void acceptLoop() {
        while (server.isOpen()) {
            SocketChannel channel;
            try {
                channel = server.accept();
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "명령 채널 연결 수락 실패: {0}", e.getClass().getSimpleName());
                continue;
            }
            if (!slots.tryAcquire()) {
                closeQuietly(channel);
                LOG.log(Level.WARNING, "명령 채널 동시 연결 상한({0})을 넘어 연결을 닫았습니다", MAX_CONNECTIONS);
                continue;
            }
            connections.execute(() -> {
                try {
                    serve(channel);
                } finally {
                    slots.release();
                }
            });
        }
    }

    private void serve(SocketChannel channel) {
        ScheduledFuture<?> deadline = timeouts.schedule(() -> closeQuietly(channel),
                READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        try (channel) {
            ControlRequest request;
            try {
                request = ControlCodec.read(channel, ControlRequest.class);
            } catch (ControlCodec.MalformedMessageException e) {
                deadline.cancel(false);
                ControlCodec.write(channel, ControlResponse.rejected(ControlCodes.BAD_REQUEST, e.getMessage(), null));
                return;
            }
            deadline.cancel(false);
            ControlResponse response = commands.submit(() -> handle(request)).get();
            ControlCodec.write(channel, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 읽기 시간 초과로 닫힌 연결 등. 요청 내용은 기록하지 않는다
            LOG.log(Level.DEBUG, "명령 채널 연결 처리 중단: {0}", e.getClass().getSimpleName());
        }
    }

    private ControlResponse handle(ControlRequest request) {
        try {
            return handler.apply(request);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "명령 처리 오류: {0} ({1})", request.command(), e.getClass().getSimpleName());
            return ControlResponse.rejected(ControlCodes.INTERNAL, "실전 엔진 내부 오류로 명령을 처리하지 못했습니다", null);
        }
    }

    private static void prepareDirectory(Path dir) throws IOException {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!Files.isDirectory(dir)) {
            if (posix) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(dir);
            }
        }
        if (posix) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static void restrictSocketFile(Path socket) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** 남은 소켓 파일이 살아 있는 엔진 것이면 시작을 거부하고, 죽은 것이면 지운다. */
    private static void removeStaleSocket(Path socket) throws IOException {
        if (!Files.exists(socket)) {
            return;
        }
        if (isListening(socket)) {
            throw new IOException("다른 실전 엔진이 이미 명령 채널을 쓰고 있습니다: " + socket.getFileName());
        }
        Files.deleteIfExists(socket);
    }

    private static boolean isListening(Path socket) {
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return Thread.ofPlatform().name(name).daemon(true).factory();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // 닫는 중 오류는 무시
        }
        commands.shutdownNow();
        connections.shutdownNow();
        timeouts.shutdownNow();
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
            // 다음 기동 때 남은 소켓으로 보고 지운다
        }
    }
}
