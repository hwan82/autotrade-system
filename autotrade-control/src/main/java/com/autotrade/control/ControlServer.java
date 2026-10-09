package com.autotrade.control;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 실전 엔진 쪽 명령 채널 서버(ADR-006 2.2). Unix 도메인 소켓 하나를 열고 요청마다 처리기를 부른다.
 *
 * <ul>
 *   <li>올리는 명령과 PANIC(ACTIVATE, PANIC_ACCOUNT, PANIC_ALL)은 한 번에 하나씩 처리한다(단일 스레드). ACTIVATE는
 *       대기열(실행 중 포함)에 {@link #MAX_QUEUED}개까지만 받고 넘치면 {@link ControlCodes#BUSY}로 거절한다. PANIC은
 *       거절하지 않고 기다렸다가 실행한다.</li>
 *   <li>STATUS와 DEACTIVATE는 대기열을 거치지 않고 연결 스레드에서 바로 처리한다. 활성화가 오래 걸려도 상태를 볼 수 있고
 *       즉시 내릴 수 있어야 하기 때문이다(내리는 전이는 언제나 허용, D-023 공통 원칙 2). 그래서 처리기는 이 두 명령에 대해
 *       스레드 안전해야 한다.</li>
 *   <li>ACTIVATE가 대기열에서 {@link #STALE_AFTER}보다 오래 기다렸으면 실행하지 않고 {@link ControlCodes#STALE}로 버린다.
 *       실험실은 이미 응답 없음으로 처리했을 수 있어, 사람이 모르는 사이에 실전이 열리면 안 된다. PANIC은 기다렸어도 실행한다.</li>
 *   <li>동시 연결은 {@link #MAX_CONNECTIONS}개까지, 요청 한 줄을 {@link #READ_TIMEOUT} 안에 보내야 한다.</li>
 *   <li>같은 설정 폴더에서 엔진은 하나만 뜬다. {@code run/engine.lock} 파일 잠금을 잡지 못하면 시작하지 않는다
 *       (소켓 파일을 지워도 두 번째 엔진이 뜨지 않게).</li>
 *   <li>POSIX에서는 소켓 폴더를 소유자 전용(700), 소켓을 600으로 만든다. 소켓 폴더가 심볼릭 링크면 거부한다.</li>
 *   <li>서버 스레드는 모두 데몬이다. 명령 채널이 죽어도 엔진의 보유 종목 보호는 계속된다(UI-06).</li>
 * </ul>
 */
public final class ControlServer implements AutoCloseable {

    public static final int MAX_CONNECTIONS = 8;
    public static final int MAX_QUEUED = 2;
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** 실험실 클라이언트 제한 시간(15초)보다 짧게 잡는다. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(10);
    public static final String LOCK_FILE = "engine.lock";

    private static final Set<ControlCommand> DIRECT = EnumSet.of(ControlCommand.STATUS, ControlCommand.DEACTIVATE);
    private static final Logger LOG = System.getLogger(ControlServer.class.getName());

    private final Path socket;
    private final Function<ControlRequest, ControlResponse> handler;
    private final long staleAfterNanos;
    private final FileChannel lockChannel;
    // 잠금 파일에는 엔진 PID를 적는다. 소켓 파일이 사라져도 실험실이 엔진 생존을 판단할 수 있게 한다(ControlClient)
    private final FileLock lock;
    private final ServerSocketChannel server;
    private final ExecutorService commands = Executors.newSingleThreadExecutor(daemon("control-command"));
    private final ExecutorService connections = Executors.newThreadPerTaskExecutor(daemon("control-conn"));
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(daemon("control-timeout"));
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Semaphore queued = new Semaphore(MAX_QUEUED);
    private final Thread acceptor;

    private ControlServer(Path socket, Function<ControlRequest, ControlResponse> handler, Duration staleAfter)
            throws IOException {
        this.socket = socket;
        this.handler = handler;
        this.staleAfterNanos = staleAfter.toNanos();
        Path dir = socket.toAbsolutePath().getParent();
        prepareDirectory(dir);
        this.lockChannel = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            // 같은 프로세스 안에서 이미 잡은 잠금(테스트 등). 다른 엔진과 같게 본다
            acquired = null;
        } catch (IOException | RuntimeException e) {
            lockChannel.close();
            throw e;
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("다른 실전 엔진이 이미 실행 중입니다(" + LOCK_FILE + " 잠금)");
        }
        this.lock = acquired;
        try {
            lockChannel.truncate(0);
            lockChannel.write(ByteBuffer.wrap(
                    Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.US_ASCII)), 0);
            lockChannel.force(false);
        } catch (IOException e) {
            releaseLock();
            throw e;
        }
        ServerSocketChannel opened = null;
        try {
            removeStaleSocket(socket);
            opened = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                opened.bind(UnixDomainSocketAddress.of(socket));
            } catch (IOException e) {
                throw new IOException("명령 채널 소켓을 열지 못했습니다(" + e.getMessage()
                        + "). 소켓 경로가 길면(약 100자 초과) AUTOTRADE_CONFIG_DIR을 짧은 경로로 바꾸세요", e);
            }
            restrictSocketFile(socket);
        } catch (IOException | RuntimeException e) {
            if (opened != null) {
                opened.close();
                Files.deleteIfExists(socket);
            }
            releaseLock();
            throw e;
        }
        this.server = opened;
        this.acceptor = daemon("control-accept").newThread(this::acceptLoop);
        acceptor.start();
    }

    /** 소켓을 열고 요청을 받기 시작한다. */
    public static ControlServer start(Path socket, Function<ControlRequest, ControlResponse> handler)
            throws IOException {
        return start(socket, handler, STALE_AFTER);
    }

    /** 오래 기다린 ACTIVATE를 버리는 기준을 정해 시작한다(테스트용). */
    static ControlServer start(Path socket, Function<ControlRequest, ControlResponse> handler, Duration staleAfter)
            throws IOException {
        return new ControlServer(Objects.requireNonNull(socket, "socket"), Objects.requireNonNull(handler, "handler"),
                Objects.requireNonNull(staleAfter, "staleAfter"));
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
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "명령 채널 연결 수락 실패: {0}", e.getClass().getSimpleName());
                if (!backoff()) {
                    return;
                }
                continue;
            }
            if (!slots.tryAcquire()) {
                closeQuietly(channel);
                LOG.log(Level.WARNING, "명령 채널 동시 연결 상한({0})을 넘어 연결을 닫았습니다", MAX_CONNECTIONS);
                continue;
            }
            try {
                connections.execute(() -> {
                    try {
                        serve(channel);
                    } finally {
                        slots.release();
                    }
                });
            } catch (RuntimeException e) {
                // 종료 중(RejectedExecution) 등
                slots.release();
                closeQuietly(channel);
            }
        }
    }

    private static boolean backoff() {
        try {
            Thread.sleep(100);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void serve(SocketChannel channel) {
        ScheduledFuture<?> deadline = timeouts.schedule(() -> closeQuietly(channel),
                READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        try (channel) {
            ControlRequest request;
            try {
                request = ControlCodec.readRequest(channel);
            } catch (ControlCodec.MalformedMessageException e) {
                if (deadline.cancel(false)) {
                    ControlCodec.write(channel, ControlResponse.rejected(ControlCodes.BAD_REQUEST, e.getMessage(), null));
                }
                return;
            }
            if (!deadline.cancel(false)) {
                // 읽기 시간 초과로 이미 닫힌 연결. 늦게 도착한 요청은 처리하지 않는다
                return;
            }
            ControlCodec.write(channel, dispatch(request));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 읽기 시간 초과로 닫힌 연결 등. 요청 내용은 기록하지 않는다
            LOG.log(Level.DEBUG, "명령 채널 연결 처리 중단: {0}", e.getClass().getSimpleName());
        }
    }

    private ControlResponse dispatch(ControlRequest request) throws Exception {
        if (DIRECT.contains(request.command())) {
            return handle(request);
        }
        boolean panic = request.command() == ControlCommand.PANIC_ACCOUNT || request.command() == ControlCommand.PANIC_ALL;
        // PANIC은 대기열 상한과 상관없이 받는다(내리는 방향은 거절하지 않음, D-023 원칙 2). 연결 상한이 전체 수를 묶는다
        if (panic) {
            return commands.submit(() -> handle(request)).get();
        }
        if (!queued.tryAcquire()) {
            return ControlResponse.rejected(ControlCodes.BUSY,
                    "실전 엔진이 다른 명령을 처리 중입니다. 상태를 새로 읽은 뒤 다시 보내세요", null);
        }
        long enqueuedAt = System.nanoTime();
        try {
            return commands.submit(() -> {
                long waited = System.nanoTime() - enqueuedAt;
                if (request.command() == ControlCommand.ACTIVATE && waited > staleAfterNanos) {
                    return ControlResponse.rejected(ControlCodes.STALE,
                            "활성화 요청이 너무 오래 기다려 실행하지 않았습니다. 상태를 확인하고 다시 시도하세요", null);
                }
                return handle(request);
            }).get();
        } finally {
            queued.release();
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
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("명령 채널 폴더가 심볼릭 링크라 쓰지 않습니다: " + dir.getFileName());
        }
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
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

    /**
     * 남은 소켓 파일을 정리한다. 엔진 잠금을 잡은 뒤에 부르므로 남은 소켓은 죽은 엔진의 것이다. 그래도 누가 듣고 있으면
     * (잠금을 쓰지 않는 다른 프로그램) 지우지 않고 시작을 거부한다.
     */
    private static void removeStaleSocket(Path socket) throws IOException {
        if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isListening(socket)) {
            throw new IOException("다른 프로그램이 이미 명령 채널 소켓을 쓰고 있습니다: " + socket.getFileName());
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

    private static ThreadFactory daemon(String name) {
        return Thread.ofPlatform().name(name).daemon(true).factory();
    }

    private void releaseLock() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
            // 채널을 닫으면 풀린다
        }
        try {
            lockChannel.close();
        } catch (IOException ignored) {
            // 프로세스가 끝나면 풀린다
        }
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
        releaseLock();
    }
}
