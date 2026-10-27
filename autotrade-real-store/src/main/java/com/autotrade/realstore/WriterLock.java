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
 * <p>잠그는 구간은 파일 내용이 아니라 {@link #LOCK_OFFSET}의 1바이트다. 내용(부팅 번호·PID)은 잠금 구간 밖에 있어 Windows에서도
 * 다른 핸들로 경로를 열어 읽을 수 있다 {@code [추론]}. 다른 프로세스가 파일 전체를 잠그려 하면({@code tryLock()}은 0부터
 * {@code Long.MAX_VALUE}까지) 이 구간과 겹쳐 실패한다.
 *
 * <p>리눅스·macOS는 잠긴 파일도 지울 수 있고, Windows도 JDK가 지우기를 허용하는 공유 모드로 열어 잠긴 파일이 지워질 수 있다
 * {@code [추론]}. 그래서 잡은 뒤 파일에 자기 부팅 번호와 PID를 적어 두고 {@link #check()}로 경로의 파일이 그대로 있는지, 경로로
 * 읽은 내용이 같은지를 본다(POSIX에서는 {@code fileKey}도 본다. Windows의 {@code fileKey}는 null이다). 경로로 읽지 못하면
 * 문제로 본다. 잠금 파일이 지워진 뒤 다른 프로세스가 같은 이름의 새 파일을 만들어 잠그면 경로 읽기가 막히거나 내용이 달라
 * 어느 쪽이든 걸린다(PR #248 독립 QA 발견 1). 엔진이 쥔 채널로 읽어 대조하지 않는다. 그 채널은 처음 잡은 옛 파일이라 늘 같다.
 */
final class WriterLock implements AutoCloseable {

    /** 잠그는 1바이트의 위치. 내용(수십 바이트)과 겹치지 않을 만큼 멀다. */
    static final long LOCK_OFFSET = 1L << 62;

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
            lock = channel.tryLock(LOCK_OFFSET, 1, false);
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
        byte[] got;
        try {
            got = readByPath();
        } catch (IOException e) {
            // 자기 내용은 잠금 구간 밖이라 늘 읽혀야 한다. 못 읽으면 다른 프로세스가 같은 이름의 새 파일을 잡았거나 파일이
            // 바뀐 것으로 본다(안전 쪽)
            return Optional.of("엔진 저장소 쓰기 잠금 파일(" + path.getFileName() + ")을 경로로 읽지 못했습니다(다른 쓰기 주체 의심: "
                    + e.getClass().getSimpleName() + ")");
        }
        if (!Arrays.equals(got, stamp)) {
            return Optional.of("엔진 저장소 쓰기 잠금 파일(" + path.getFileName() + ")의 내용이 바뀌었습니다(다른 쓰기 주체 의심)");
        }
        return Optional.empty();
    }

    private byte[] readByPath() throws IOException {
        try (FileChannel read = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buf = ByteBuffer.allocate(stamp.length + 1);
            while (buf.hasRemaining() && read.read(buf) >= 0) {
                // 끝까지 읽는다(최대 stamp + 1 바이트)
            }
            return Arrays.copyOf(buf.array(), buf.position());
        }
    }

    /** 시험용: Windows처럼 {@code fileKey}가 없는 경우를 흉내 낸다. */
    void forgetFileKeyForTest() {
        this.key = null;
    }

    /** 시험용: 잡은 잠금 구간의 시작 위치. */
    long lockedPosition() {
        return lock.position();
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
