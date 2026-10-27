package com.autotrade.marketdata.enginefeed.server;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/**
 * 엔진 전달 서버의 듣는 소켓(ADR-016 2.2). 폴더 700·소켓 600(POSIX). 소켓 파일이 지워지면 다시 만들고, 다른 파일이 그 자리를
 * 차지하면 빼앗지 않는다. 이미 받은 연결은 듣는 소켓을 바꿔도 끊기지 않는다.
 *
 * <p>"이 서버가 만든 파일인지"는 만들 때 읽은 파일 키(POSIX는 장치·inode)로 본다. 파일 키를 주지 않는 파일 시스템(Windows
 * `[가정]`)에서는 파일이 있는지만 본다.
 */
final class EngineFeedListener implements AutoCloseable {

    private static final Logger LOG = System.getLogger(EngineFeedListener.class.getName());

    private final Path socket;
    private volatile ServerSocketChannel channel;
    /** 만든 소켓 파일의 키(없으면 null). 타이머 스레드와 만들 때만 쓴다. */
    private Object fileKey;
    private boolean foreignLogged;
    private long rebindFailures;
    private volatile boolean closed;

    private EngineFeedListener(Path socket) {
        this.socket = socket;
    }

    /** @throws IOException 열 수 없거나 다른 프로세스가 이미 그 경로에서 듣고 있을 때 */
    static EngineFeedListener open(Path socket) throws IOException {
        EngineFeedListener l = new EngineFeedListener(socket.toAbsolutePath());
        prepareDirectory(l.socket.getParent());
        if (Files.exists(l.socket, LinkOption.NOFOLLOW_LINKS)) {
            if (isListening(l.socket)) {
                throw new IOException("다른 프로세스가 이미 엔진 시세 소켓을 쓰고 있습니다: " + l.socket.getFileName());
            }
            Files.deleteIfExists(l.socket);
        }
        l.bind();
        return l;
    }

    ServerSocketChannel channel() {
        return channel;
    }

    private synchronized void bind() throws IOException {
        ServerSocketChannel opened = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            opened.bind(UnixDomainSocketAddress.of(socket));
            if (posix()) {
                Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            }
            fileKey = keyOf(socket);
        } catch (IOException e) {
            opened.close();
            throw new IOException("엔진 시세 소켓을 열지 못했습니다(" + e.getMessage()
                    + "). 소켓 경로가 길면(약 100자 초과) AUTOTRADE_CONFIG_DIR을 짧은 경로로 바꾸세요", e);
        }
        ServerSocketChannel old = channel;
        channel = opened;
        foreignLogged = false;
        if (old != null) {
            try {
                old.close(); // 받기 스레드가 깨어나 새 소켓으로 옮긴다. 받은 연결은 그대로
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
    }

    /** 1초마다 부른다. 파일이 없으면 다시 만들고, 다른 파일이면 로그만 남긴다. */
    synchronized void check() {
        if (closed) {
            return;
        }
        Object key;
        try {
            key = Files.exists(socket, LinkOption.NOFOLLOW_LINKS) ? keyOf(socket) : null;
        } catch (NoSuchFileException e) {
            key = null;
        } catch (IOException e) {
            return;
        }
        boolean missing = !Files.exists(socket, LinkOption.NOFOLLOW_LINKS);
        if (missing) {
            try {
                prepareDirectory(socket.getParent());
                bind();
                rebindFailures = 0;
                LOG.log(Level.ERROR, "엔진 시세 소켓 파일이 없어져 다시 만들었습니다. 붙어 있던 엔진 연결은 그대로입니다");
            } catch (IOException | RuntimeException e) {
                rebindFailures++;
                if ((rebindFailures & (rebindFailures - 1)) == 0) {
                    LOG.log(Level.ERROR, "엔진 시세 소켓을 다시 만들지 못했습니다({0}번째): {1}", rebindFailures,
                            e.getClass().getName());
                }
            }
            return;
        }
        if (fileKey != null && !Objects.equals(fileKey, key) && !foreignLogged) {
            foreignLogged = true;
            LOG.log(Level.ERROR, "엔진 시세 소켓 자리를 다른 파일이 차지했습니다. 빼앗지 않습니다(엔진은 새로 붙지 못합니다): {0}",
                    socket.getFileName());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
        try {
            // 다른 프로세스가 차지한 파일은 지우지 않는다
            if (fileKey == null || Objects.equals(fileKey, keyOf(socket))) {
                Files.deleteIfExists(socket);
            }
        } catch (NoSuchFileException ignored) {
            // 이미 없음
        } catch (IOException e) {
            LOG.log(Level.WARNING, "엔진 시세 소켓 파일을 지우지 못했습니다: {0}", e.getClass().getName());
        }
    }

    private static Object keyOf(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    static void prepareDirectory(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("엔진 시세 소켓 폴더가 심볼릭 링크라 쓰지 않습니다: " + dir.getFileName());
        }
        boolean posix = posix();
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

    static boolean isListening(Path socket) {
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
