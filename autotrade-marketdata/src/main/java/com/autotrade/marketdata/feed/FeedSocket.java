package com.autotrade.marketdata.feed;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 시세 전달 소켓 위치와 폴더 준비(ADR-010 2.1). 설정 폴더 아래 {@code run/market-feed.sock}(Unix 도메인 소켓). 실전 명령
 * 채널(ADR-006 2.2)과 같은 방식이라 네트워크 포트를 열지 않고, POSIX에서는 폴더 700·소켓 600으로 같은 OS 사용자만 연결한다.
 */
public final class FeedSocket {

    public static final String DIR = "run";
    public static final String FILE = "market-feed.sock";

    private FeedSocket() {}

    /** @param configDir 설정 폴더({@code runtime-config.json}이 있는 곳) */
    public static Path path(Path configDir) {
        return configDir.toAbsolutePath().resolve(DIR).resolve(FILE);
    }

    static void prepareDirectory(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("시세 전달 소켓 폴더가 심볼릭 링크라 쓰지 않습니다: " + dir.getFileName());
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

    static void restrictSocketFile(Path socket) throws IOException {
        if (posix()) {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** 남은 소켓 파일을 지운다. 누가 듣고 있으면(다른 게이트웨이) 지우지 않고 거부한다. */
    static void removeStaleSocket(Path socket) throws IOException {
        if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isListening(socket)) {
            throw new IOException("다른 게이트웨이가 이미 시세 전달 소켓을 쓰고 있습니다: " + socket.getFileName());
        }
        Files.deleteIfExists(socket);
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
