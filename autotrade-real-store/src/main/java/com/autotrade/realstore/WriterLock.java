package com.autotrade.realstore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * 원장 쓰기 주체는 한 번에 하나(ADR-015 2.9). {@code writer.lock}을 {@code FileChannel.tryLock}으로 잡지 못하면 기동을 거부한다.
 *
 * <p>리눅스·macOS는 잠긴 파일도 지울 수 있고, Windows도 JDK가 지우기를 허용하는 공유 모드로 열어 잠긴 파일이 지워질 수 있다
 * {@code [추론]}. 그래서 잡은 뒤 파일에 자기 부팅 번호와 PID를 적어 두고 {@link #check()}로 파일이 그대로 있는지, 내용이 같은지를
 * 본다(내용 대조가 기본, POSIX에서는 {@code fileKey}도 본다. Windows의 {@code fileKey}는 null이다).
 */
final class WriterLock implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private byte[] stamp;
    private Object key;

    private WriterLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 잠금을 잡는다.
     *
     * @throws StoreRefusal 다른 프로세스가 잡고 있거나 잠금 파일이 심볼릭 링크일 때
     */
    static WriterLock acquire(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new StoreRefusal("엔진 저장소 쓰기 잠금 파일이 심볼릭 링크라 기동하지 않습니다: " + path.getFileName());
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // 같은 JVM 안에서 이미 잡음(시험·잘못된 이중 기동)
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new StoreRefusal("다른 프로세스가 엔진 저장소 쓰기 잠금(" + path.getFileName()
                    + ")을 잡고 있어 기동하지 않습니다. 다른 실전 엔진이나 감시 긴급 경로가 돌고 있는지 확인하세요");
        }
        StorePaths.restrictFile(path);
        return new WriterLock(path, channel, lock);
    }

    /** 잠금 파일에 부팅 번호와 PID를 적는다(사람이 보는 값이자 교체 감지 기준). */
    void stamp(long boot, long pid) throws IOException {
        byte[] content = ("boot=" + boot + " pid=" + pid + "\n").getBytes(StandardCharsets.UTF_8);
        channel.truncate(0);
        channel.position(0);
        ByteBuffer buf = ByteBuffer.wrap(content);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(true);
        this.stamp = content;
        this.key = fileKey();
    }

    /** 잠금 파일이 그대로인지. 문제가 있으면 그 설명(경로 이름만, 값 없음). */
    Optional<String> check() {
        Objects.requireNonNull(stamp, "stamp 전에 확인하지 않습니다");
        if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("엔진 저장소 쓰기 잠금 파일(" + path.getFileName() + ")이 지워졌거나 바뀌었습니다");
        }
        Object now = fileKey();
        if (key != null && !key.equals(now)) {
            return Optional.of("엔진 저장소 쓰기 잠금 파일(" + path.getFileName() + ")이 다른 파일로 바뀌었습니다");
        }
        try (FileChannel read = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buf = ByteBuffer.allocate(stamp.length + 1);
            while (buf.hasRemaining() && read.read(buf) >= 0) {
                // 끝까지 읽는다(최대 stamp + 1 바이트)
            }
            byte[] got = Arrays.copyOf(buf.array(), buf.position());
            if (!Arrays.equals(got, stamp)) {
                return Optional.of("엔진 저장소 쓰기 잠금 파일(" + path.getFileName() + ")의 내용이 바뀌었습니다(다른 쓰기 주체 의심)");
            }
        } catch (IOException e) {
            return Optional.of("엔진 저장소 쓰기 잠금 파일(" + path.getFileName() + ")을 읽지 못했습니다");
        }
        return Optional.empty();
    }

    private Object fileKey() {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
