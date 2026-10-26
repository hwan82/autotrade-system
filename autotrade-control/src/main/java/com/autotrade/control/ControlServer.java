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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 실전 엔진 쪽 명령 채널 서버(ADR-006 2.2, D-142). Unix 도메인 소켓 하나를 열고 연결마다 처리기({@link ControlHandler})를 부른다.
 *
 * <ul>
 *   <li>명령의 순서·계좌별 처리·기한·대기 상한은 처리기가 정한다. 서버는 연결마다 다른 스레드에서 처리기를 바로 부르고, 연결을 받은
 *       시각을 넘긴다(기한의 기준).</li>
 *   <li>동시 연결은 {@link #MAX_CONNECTIONS}개까지, 요청 한 줄을 {@link #READ_TIMEOUT} 안에 보내야 한다. 처리기는 오래 기다리는
 *       연결을 {@link #MAX_CONNECTIONS} − {@link #RESERVED_FOR_NEW}개 이하로 묶어, 새로 들어오는 내리기·PANIC이 읽힐 자리를 남긴다
 *       (C5). 상한에 닿으면 {@link Listener}로 알린다(엔진이 긴급 알림).</li>
 *   <li>같은 설정 폴더에서 엔진은 하나만 뜬다. {@code run/engine.lock} 파일 잠금을 잡지 못하면 시작하지 않는다. 잠금 파일은
 *       심볼릭 링크를 따라가지 않고 연다.</li>
 *   <li>{@link #CHECK_INTERVAL}마다 소켓·잠금 파일·소켓 폴더가 그대로인지 본다(C6). 누가 지웠으면 다시 만들어 화면 명령이 이
 *       엔진에 닿게 하고, 다른 엔진이 잠금이나 소켓 자리를 잡았으면 빼앗지 않고 알린다. 어느 쪽이든 {@link Listener}로 알린다.</li>
 *   <li>POSIX에서는 소켓 폴더를 소유자 전용(700), 소켓을 600으로 만든다. 소켓 폴더가 심볼릭 링크면 거부한다.</li>
 *   <li>닫을 때는 새 연결을 받지 않고, 진행 중인 연결이 응답을 마칠 때까지 최대 {@link #CLOSE_WAIT} 기다린다(C7). 소켓 파일은
 *       이 서버가 만든 것일 때만 지운다(다른 엔진의 소켓을 지우지 않게).</li>
 *   <li>서버 스레드는 모두 데몬이다. 명령 채널이 죽어도 엔진의 보유 종목 보호는 계속된다(UI-06).</li>
 * </ul>
 */
public final class ControlServer implements AutoCloseable {

    public static final int MAX_CONNECTIONS = 8;
    /** 처리기가 오래 붙잡지 않고 비워 두는 연결 자리 수. 새로 오는 내리기·PANIC 요청이 읽힐 자리다(C5). */
    public static final int RESERVED_FOR_NEW = 2;
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** 소켓·잠금 파일 자가 확인 주기(C6). */
    public static final Duration CHECK_INTERVAL = Duration.ofSeconds(1);
    /** 닫을 때 진행 중인 연결을 기다리는 상한(C7). */
    public static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    /** 기존 엔진 확인용 시험 연결의 상한(C9). 넘기면 듣는 쪽이 있다고 본다(안전한 쪽: 기동 거부·소켓 안 지움). */
    public static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);
    public static final String LOCK_FILE = "engine.lock";

    /** 연결 상한 알림을 다시 보내기까지의 간격. */
    private static final long SATURATION_REPORT_NANOS = Duration.ofMinutes(1).toNanos();
    private static final Logger LOG = System.getLogger(ControlServer.class.getName());

    /** 명령 채널 상태 변화. 엔진이 긴급 알림으로 바꾼다. 빨리 돌아와야 하고 던져도 서버는 계속 돈다. */
    @FunctionalInterface
    public interface Listener {
        void onEvent(Event event, String detail);

        Listener NONE = (event, detail) -> { };
    }

    /** 명령 채널 상태 변화 종류. */
    public enum Event {
        /** 동시 연결 상한에 닿아 연결을 닫았다. */
        CONNECTIONS_SATURATED,
        /** 지워진 소켓 폴더를 다시 만들었다. */
        RUN_DIR_RECREATED,
        /** 지워지거나 바뀐 잠금 파일을 다시 만들고 잠갔다. */
        LOCK_RECREATED,
        /** 잠금 파일 자리를 다른 프로세스(다른 엔진)가 잠그고 있다. */
        LOCK_TAKEN,
        /** 지워진 소켓을 다시 만들었다. 화면 명령이 다시 이 엔진에 닿는다. */
        SOCKET_RECREATED,
        /** 소켓 자리를 다른 프로세스(다른 엔진)가 듣고 있다. 화면 명령이 이 엔진에 닿지 않는다. */
        SOCKET_TAKEN,
        /** 자가 확인이나 다시 만들기가 실패했다. */
        CHECK_FAILED
    }

    private final Path socket;
    private final Path dir;
    private final Path lockPath;
    private final ControlHandler handler;
    private final Listener listener;
    private final ExecutorService connections = Executors.newThreadPerTaskExecutor(daemon("control-conn"));
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(daemon("control-timeout"));
    private final ScheduledExecutorService checker = Executors.newSingleThreadScheduledExecutor(daemon("control-check"));
    private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
    private final Object state = new Object();
    // 아래는 state 잠금 안에서 바꾼다(acceptor·serve는 server 참조만 읽는다)
    private FileChannel lockChannel;
    // 잠금 파일에는 엔진 PID를 적는다. 소켓 파일이 사라져도 실험실이 엔진 생존을 판단할 수 있게 한다(ControlClient)
    private FileLock lock;
    private Object lockKey;
    private volatile ServerSocketChannel server;
    private Object socketKey;
    private boolean lockTakenReported;
    private boolean socketTakenReported;
    private boolean closed;
    private volatile String silenced;
    private long lastSaturationReport = System.nanoTime() - SATURATION_REPORT_NANOS;

    private ControlServer(Path socket, ControlHandler handler, Listener listener, Duration checkInterval) throws IOException {
        this.socket = socket.toAbsolutePath();
        this.handler = handler;
        this.listener = listener;
        this.dir = this.socket.getParent();
        this.lockPath = dir.resolve(LOCK_FILE);
        prepareDirectory(dir);
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = tryLock(channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (acquired == null) {
            channel.close();
            throw new IOException("다른 실전 엔진이 이미 실행 중입니다(" + LOCK_FILE + " 잠금)");
        }
        try {
            writePid(channel);
        } catch (IOException e) {
            // 잠금을 잡은 채로 지운다. 빈 잠금 파일이 남으면 실험실이 엔진이 없는데도 UNKNOWN으로 본다(C8)
            Files.deleteIfExists(lockPath);
            release(acquired, channel);
            throw e;
        }
        this.lockChannel = channel;
        this.lock = acquired;
        this.lockKey = fileKey(lockPath);
        ServerSocketChannel opened = null;
        try {
            removeStaleSocket(this.socket);
            opened = bind(this.socket);
        } catch (IOException | RuntimeException e) {
            release(acquired, channel);
            throw e;
        }
        this.server = opened;
        this.socketKey = fileKey(this.socket);
        startAcceptor(opened);
        if (!checkInterval.isZero()) {
            checker.scheduleWithFixedDelay(this::checkQuietly, checkInterval.toMillis(), checkInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /** 소켓을 열고 요청을 받기 시작한다. */
    public static ControlServer start(Path socket, ControlHandler handler, Listener listener) throws IOException {
        return start(socket, handler, listener, CHECK_INTERVAL);
    }

    /** 자가 확인 주기를 정해 시작한다. 0이면 저절로 돌지 않는다(시험이 {@link #checkIntegrity()}를 직접 부름). */
    static ControlServer start(Path socket, ControlHandler handler, Listener listener, Duration checkInterval)
            throws IOException {
        if (checkInterval.isNegative()) {
            throw new IllegalArgumentException("자가 확인 주기는 0 이상이어야 합니다");
        }
        return new ControlServer(Objects.requireNonNull(socket, "socket"), Objects.requireNonNull(handler, "handler"),
                Objects.requireNonNull(listener, "listener"), checkInterval);
    }

    /** 받은 시각을 쓰지 않는 처리기로 시작한다(간단한 처리기·시험용). 알림 없이 자가 확인은 돈다. */
    public static ControlServer start(Path socket, Function<ControlRequest, ControlResponse> handler) throws IOException {
        Objects.requireNonNull(handler, "handler");
        return start(socket, (request, receivedAt) -> handler.apply(request), Listener.NONE, CHECK_INTERVAL);
    }

    public Path socket() {
        return socket;
    }

    /**
     * 이 엔진이 같은 설정 폴더의 두 번째(늦게 뜬) 엔진이라고 판단됐을 때 부른다. 그 뒤로는 어떤 요청에도 답하지 않는다. 자리도
     * 내준다: 자가 확인을 멈추고(다시 만들지 않음), 듣기 소켓을 닫고, 잠금을 푼다. 파일은 지우지 않는다. 그러면 먼저 뜬 엔진의 자가
     * 확인이 다음 주기({@link #CHECK_INTERVAL})에 잠금과 소켓을 다시 잡아 화면 명령이 먼저 뜬 엔진에 닿는다(D-140 ③ 3-R, C6,
     * architect PR #240 N1). 되돌릴 수 없다.
     */
    public void silence(String reason) {
        Objects.requireNonNull(reason, "reason");
        ServerSocketChannel current;
        synchronized (state) {
            if (silenced != null) {
                return;
            }
            silenced = reason;
            checker.shutdownNow();
            current = server;
            release(lock, lockChannel);
            lock = null;
            lockChannel = null;
        }
        closeQuietly(current);
        LOG.log(Level.ERROR, "명령 채널이 응답을 멈추고 소켓·잠금 자리를 내줍니다: {0}", reason);
    }

    public boolean silenced() {
        return silenced != null;
    }

    private void startAcceptor(ServerSocketChannel channel) {
        daemon("control-accept").newThread(() -> acceptLoop(channel)).start();
    }

    private void acceptLoop(ServerSocketChannel channel) {
        while (channel.isOpen()) {
            SocketChannel connection;
            try {
                connection = channel.accept();
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "명령 채널 연결 수락 실패: {0}", e.getClass().getSimpleName());
                if (!backoff()) {
                    return;
                }
                continue;
            }
            long receivedAt = System.nanoTime();
            if (!slots.tryAcquire()) {
                closeQuietly(connection);
                LOG.log(Level.WARNING, "명령 채널 동시 연결 상한({0})을 넘어 연결을 닫았습니다", MAX_CONNECTIONS);
                reportSaturation(receivedAt);
                continue;
            }
            try {
                connections.execute(() -> {
                    try {
                        serve(connection, receivedAt);
                    } finally {
                        slots.release();
                    }
                });
            } catch (RuntimeException e) {
                // 종료 중(RejectedExecution) 등
                slots.release();
                closeQuietly(connection);
            }
        }
    }

    private void reportSaturation(long now) {
        boolean report;
        synchronized (state) {
            report = now - lastSaturationReport >= SATURATION_REPORT_NANOS;
            if (report) {
                lastSaturationReport = now;
            }
        }
        if (report) {
            notify(Event.CONNECTIONS_SATURATED, "명령 채널 동시 연결 상한(" + MAX_CONNECTIONS
                    + "개)에 닿아 새 연결을 닫았습니다. 같은 PC의 다른 프로그램이 연결을 붙잡고 있을 수 있습니다");
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

    private void serve(SocketChannel channel, long receivedAt) {
        if (silenced != null) {
            closeQuietly(channel);
            return;
        }
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
            if (silenced != null) {
                return;
            }
            ControlResponse response = handle(request, receivedAt);
            if (silenced != null) {
                // 처리하는 사이 응답을 멈췄다. 응답을 멈춘 엔진의 결과를 화면에 보이지 않는다(PR #240 QA 발견 1 (b))
                return;
            }
            ControlCodec.write(channel, response);
        } catch (Exception e) {
            // 읽기 시간 초과로 닫힌 연결 등. 요청 내용은 기록하지 않는다
            LOG.log(Level.DEBUG, "명령 채널 연결 처리 중단: {0}", e.getClass().getSimpleName());
        }
    }

    private ControlResponse handle(ControlRequest request, long receivedAt) {
        try {
            return handler.handle(request, receivedAt);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "명령 처리 오류: {0} ({1})", request.command(), e.getClass().getSimpleName());
            return ControlResponse.rejected(ControlCodes.INTERNAL, "실전 엔진 내부 오류로 명령을 처리하지 못했습니다", null);
        }
    }

    // ---- 자가 확인(C6) ----

    private void checkQuietly() {
        try {
            checkIntegrity();
        } catch (Throwable e) {
            // 예약 작업이 Throwable로 끝나면 다음 주기가 조용히 취소된다
            LOG.log(Level.WARNING, "명령 채널 자가 확인 실패: {0}", e.getClass().getSimpleName());
        }
    }

    /**
     * 소켓 폴더·잠금 파일·소켓이 기동 때 만든 그대로인지 한 번 확인한다({@link #CHECK_INTERVAL}마다 저절로 돈다, 시험은 직접 부른다).
     * POSIX는 파일 식별값(inode)으로 바뀜까지 보고, 식별값이 없는 OS(Windows)는 있는지만 본다.
     */
    void checkIntegrity() {
        synchronized (state) {
            if (closed || silenced != null) {
                return;
            }
            try {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(dir)) {
                        notify(Event.CHECK_FAILED, "명령 채널 폴더가 심볼릭 링크로 바뀌었습니다. 다시 만들지 않습니다: " + dir.getFileName());
                        return;
                    }
                    prepareDirectory(dir);
                    notify(Event.RUN_DIR_RECREATED, "명령 채널 폴더(" + dir.getFileName() + ")가 지워져 다시 만들었습니다");
                }
                checkLock();
                checkSocket();
            } catch (IOException | RuntimeException e) {
                notify(Event.CHECK_FAILED, "명령 채널 자가 확인 중 오류(" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    private void checkLock() throws IOException {
        boolean same = Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && (lockKey == null || lockKey.equals(fileKey(lockPath)));
        if (same) {
            lockTakenReported = false;
            return;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            if (!lockTakenReported) {
                lockTakenReported = true;
                notify(Event.LOCK_TAKEN, "잠금 파일(" + LOCK_FILE + ")이 바뀌었는데 다시 열지 못했습니다("
                        + e.getClass().getSimpleName() + ")");
            }
            return;
        }
        FileLock acquired;
        try {
            acquired = tryLock(channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (acquired == null) {
            channel.close();
            if (!lockTakenReported) {
                lockTakenReported = true;
                notify(Event.LOCK_TAKEN, "잠금 파일(" + LOCK_FILE + ")이 지워진 뒤 다른 프로세스가 새 잠금을 잡고 있습니다."
                        + " 같은 설정 폴더로 실전 엔진이 하나 더 떴을 수 있습니다");
            }
            return;
        }
        try {
            writePid(channel);
        } catch (IOException e) {
            release(acquired, channel);
            throw e;
        }
        FileLock oldLock = lock;
        FileChannel oldChannel = lockChannel;
        lock = acquired;
        lockChannel = channel;
        lockKey = fileKey(lockPath);
        lockTakenReported = false;
        release(oldLock, oldChannel);
        notify(Event.LOCK_RECREATED, "잠금 파일(" + LOCK_FILE + ")이 지워지거나 바뀌어 다시 만들고 잠갔습니다");
    }

    private void checkSocket() throws IOException {
        boolean exists = Files.exists(socket, LinkOption.NOFOLLOW_LINKS);
        if (exists && (socketKey == null || socketKey.equals(fileKey(socket)))) {
            socketTakenReported = false;
            return;
        }
        if (exists) {
            if (isListening(socket)) {
                // 다른 엔진(또는 다른 프로그램)이 그 자리에서 듣고 있다. 빼앗지 않는다
                if (!socketTakenReported) {
                    socketTakenReported = true;
                    notify(Event.SOCKET_TAKEN, "명령 채널 소켓 자리를 다른 프로세스가 듣고 있습니다. 화면 명령이 이 엔진에 닿지 않습니다."
                            + " 같은 설정 폴더로 실전 엔진이 하나 더 떴을 수 있습니다");
                }
                return;
            }
            Files.deleteIfExists(socket);
        }
        ServerSocketChannel fresh = bind(socket);
        ServerSocketChannel old = server;
        server = fresh;
        socketKey = fileKey(socket);
        socketTakenReported = false;
        startAcceptor(fresh);
        closeQuietly(old);
        notify(Event.SOCKET_RECREATED, "명령 채널 소켓이 지워지거나 바뀌어 다시 만들었습니다. 화면 명령은 이 엔진으로 옵니다");
    }

    private void notify(Event event, String detail) {
        LOG.log(event == Event.SOCKET_RECREATED || event == Event.RUN_DIR_RECREATED || event == Event.LOCK_RECREATED
                ? Level.WARNING : Level.ERROR, "명령 채널: {0} {1}", event, detail);
        try {
            listener.onEvent(event, detail);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "명령 채널 알림 처리 실패: {0}", e.getClass().getSimpleName());
        }
    }

    // ---- 파일·소켓 ----

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // 같은 프로세스 안에서 이미 잡은 잠금(테스트 등). 다른 엔진과 같게 본다
            return null;
        }
    }

    /** PID를 먼저 쓰고 뒤를 자른다. 잘라 낸 뒤 쓰다 실패해 빈 파일이 되는 순간을 만들지 않는다(C8). */
    private static void writePid(FileChannel channel) throws IOException {
        byte[] pid = Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.wrap(pid);
        long position = 0;
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
        channel.truncate(pid.length);
        channel.force(false);
    }

    private static ServerSocketChannel bind(Path socket) throws IOException {
        ServerSocketChannel opened = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            try {
                opened.bind(UnixDomainSocketAddress.of(socket));
            } catch (IOException e) {
                throw new IOException("명령 채널 소켓을 열지 못했습니다(" + e.getMessage()
                        + "). 소켓 경로가 길면(약 100자 초과) AUTOTRADE_CONFIG_DIR을 짧은 경로로 바꾸세요", e);
            }
            restrictSocketFile(socket);
            return opened;
        } catch (IOException | RuntimeException e) {
            boolean bound = opened.getLocalAddress() != null;
            opened.close();
            if (bound) {
                Files.deleteIfExists(socket);
            }
            throw e;
        }
    }

    /** 파일 식별값(POSIX inode 등). 없으면 null, 파일이 없어도 null. */
    private static Object fileKey(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            return null;
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

    /**
     * 그 소켓에서 누가 듣고 있는지 시험 연결로 본다. {@link #PROBE_TIMEOUT} 안에 결론이 나지 않으면(접속 대기열이 찬 경우 등) 듣는
     * 쪽이 있다고 본다(C9). 그 경우 기동을 거부하거나 소켓을 지우지 않는 안전한 쪽이다.
     */
    static boolean isListening(Path socket) {
        ExecutorService probe = Executors.newSingleThreadExecutor(daemon("control-probe"));
        SocketChannel[] holder = new SocketChannel[1];
        try {
            Future<Boolean> connected = probe.submit(() -> {
                try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    synchronized (holder) {
                        holder[0] = channel;
                    }
                    channel.connect(UnixDomainSocketAddress.of(socket));
                    return true;
                } catch (IOException e) {
                    return false;
                }
            });
            return connected.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return true;
        } catch (ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } finally {
            synchronized (holder) {
                if (holder[0] != null) {
                    closeQuietly(holder[0]);
                }
            }
            probe.shutdownNow();
        }
    }

    private static void closeQuietly(java.nio.channels.Channel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }

    private static ThreadFactory daemon(String name) {
        return Thread.ofPlatform().name(name).daemon(true).factory();
    }

    private static void release(FileLock lock, FileChannel channel) {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
            // 채널을 닫으면 풀린다
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // 프로세스가 끝나면 풀린다
        }
    }

    /**
     * 새 연결을 받지 않고, 진행 중인 연결이 응답을 마칠 때까지 최대 {@link #CLOSE_WAIT} 기다린 뒤 닫는다(C7). 소켓 파일은 이 서버가
     * 만든 그 파일일 때만 지운다. 잠금 파일은 남긴다(PID가 죽었으니 실험실은 NOT_RUNNING으로 본다).
     */
    @Override
    public void close() {
        ServerSocketChannel current;
        synchronized (state) {
            if (closed) {
                return;
            }
            closed = true;
            current = server;
        }
        checker.shutdownNow();
        closeQuietly(current);
        connections.shutdown();
        try {
            if (!connections.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING, "명령 채널을 닫으며 {0} 안에 끝나지 않은 연결을 끊습니다", CLOSE_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connections.shutdownNow();
        timeouts.shutdownNow();
        synchronized (state) {
            try {
                Object key = fileKey(socket);
                if (key == null ? socketKey == null && Files.exists(socket, LinkOption.NOFOLLOW_LINKS) : key.equals(socketKey)) {
                    Files.deleteIfExists(socket);
                }
            } catch (IOException ignored) {
                // 다음 기동 때 남은 소켓으로 보고 지운다
            }
            release(lock, lockChannel);
        }
    }
}
