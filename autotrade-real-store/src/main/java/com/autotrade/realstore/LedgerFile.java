package com.autotrade.realstore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Function;

/**
 * 원장 하나에 줄을 붙인다(ADR-015 2.4). 쓰기 스레드({@link LedgerWriter}) 하나만 부른다. 날짜 원장은 거래일이 바뀌면 새 파일을 열고
 * 첫 줄에 체크포인트(직전 파일 이름, 마지막 {@code rseq}·{@code crc})를 쓴다.
 *
 * <p>쓰기가 실패하면 마지막으로 강제 기록까지 끝난 길이로 되돌리기를 시도한다. 되돌리지 못하면 이 원장을 "고장"으로 두고 더 쓰지
 * 않는다. 반쯤 쓴 줄 뒤에 줄을 이어 붙여 중간 줄 손상을 만들지 않기 위해서다.
 */
final class LedgerFile implements AutoCloseable {

    private final String stream;
    /** 한 파일 원장이면 그 파일, 날짜 원장이면 폴더. */
    private final Path location;
    /** 날짜 원장이면 기록 시각 → 거래일, 한 파일 원장이면 null. */
    private final Function<Instant, LocalDate> day;

    private FileChannel channel;
    private Path file;
    private LocalDate currentDay;
    private long lastRseq;
    private String lastCrc;
    /** 강제 기록까지 끝난 길이. */
    private long durableSize;
    private boolean dirty;
    private boolean broken;
    private String restartAfter;

    private LedgerFile(String stream, Path location, Function<Instant, LocalDate> day) {
        this.stream = stream;
        this.location = location;
        this.day = day;
    }

    /** 한 파일 원장. {@code scan}은 그 파일을 검사한 결과(손상이 아니어야 한다). */
    static LedgerFile single(Path file, LedgerScan.Result scan) {
        requireOk(scan);
        LedgerFile f = new LedgerFile(scan.stream(), file, null);
        f.lastRseq = scan.lastRseq();
        f.lastCrc = scan.lastCrc();
        return f;
    }

    /** 날짜 원장. {@code scan}은 그 폴더를 검사한 결과(손상이 아니어야 한다). */
    static LedgerFile dated(Path dir, LedgerScan.Result scan, Function<Instant, LocalDate> day) {
        requireOk(scan);
        LedgerFile f = new LedgerFile(scan.stream(), dir, Objects.requireNonNull(day, "day"));
        f.lastRseq = scan.lastRseq();
        f.lastCrc = scan.lastCrc();
        f.file = scan.file();
        if (scan.file() != null) {
            f.currentDay = LocalDate.parse(scan.file().getFileName().toString().substring(0, 10));
        }
        return f;
    }

    /** 손상으로 옮겨 둔 파일 뒤에 새로 시작하는 날짜 원장(경고만 하는 공통 원장, {@link EngineStore}). */
    static LedgerFile restartedAfter(Path dir, String stream, String quarantined, Function<Instant, LocalDate> day) {
        LedgerFile f = new LedgerFile(stream, dir, Objects.requireNonNull(day, "day"));
        f.restartAfter = quarantined;
        return f;
    }

    private static void requireOk(LedgerScan.Result scan) {
        if (!scan.ok()) {
            throw new IllegalArgumentException("손상된 원장에는 이어 쓰지 않습니다: " + scan.stream());
        }
    }

    String stream() {
        return stream;
    }

    boolean broken() {
        return broken;
    }

    long lastRseq() {
        return lastRseq;
    }

    /** 줄 하나를 붙인다(강제 기록은 {@link #force()}). 붙인 기록의 {@code rseq}를 돌려준다. */
    long append(long boot, Instant at, String type, ObjectNode body) throws IOException {
        if (broken) {
            throw new IOException("원장 " + stream + "은 앞선 쓰기 실패로 닫혔습니다");
        }
        try {
            ensureFile(boot, at);
            long rseq = lastRseq + 1;
            String line = LedgerCodec.encode(stream, rseq, boot, at, type, body);
            write(line);
            lastRseq = rseq;
            lastCrc = line.substring(line.length() - 10, line.length() - 2);
            return rseq;
        } catch (IOException | RuntimeException e) {
            rollback();
            throw e;
        }
    }

    /** 붙인 줄을 디스크에 강제 기록한다. 실패하면 마지막 강제 기록 길이로 되돌리기를 시도한다. */
    void force() throws IOException {
        if (!dirty || channel == null) {
            return;
        }
        try {
            channel.force(true);
            durableSize = channel.size();
            dirty = false;
        } catch (IOException e) {
            rollback();
            throw e;
        }
    }

    private void ensureFile(long boot, Instant at) throws IOException {
        if (day == null) {
            if (channel == null) {
                channel = open(location);
            }
            return;
        }
        LocalDate d = day.apply(at);
        if (currentDay != null && !d.isAfter(currentDay)) {
            d = currentDay; // 시계가 되돌아가도 앞 날짜 파일을 다시 열지 않는다
        }
        if (channel != null && d.equals(currentDay)) {
            return;
        }
        Path target = location.resolve(d + ".jsonl");
        Path previous = file;
        if (channel != null) {
            force();
            channel.close();
            channel = null;
        }
        Files.createDirectories(location);
        channel = open(target);
        file = target;
        currentDay = d;
        if (channel.size() == 0) {
            // 새 날짜 파일(또는 체크포인트 전에 꺼진 빈 파일): 첫 줄은 체크포인트
            ObjectNode cp = LedgerCodec.body();
            boolean continues = previous != null && !previous.equals(target) && lastCrc != null;
            cp.put("prevFile", continues ? previous.getFileName().toString() : null);
            cp.put("prevRseq", continues ? lastRseq : 0);
            cp.put("prevCrc", continues ? lastCrc : null);
            if (restartAfter != null) {
                cp.put("restartAfter", restartAfter);
                restartAfter = null;
            }
            if (!continues) {
                lastRseq = 0;
            }
            long rseq = lastRseq + 1;
            String line = LedgerCodec.encode(stream, rseq, boot, at, LedgerScan.CHECKPOINT, cp);
            write(line);
            lastRseq = rseq;
            lastCrc = line.substring(line.length() - 10, line.length() - 2);
        }
    }

    private FileChannel open(Path target) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("원장 파일이 심볼릭 링크라 쓰지 않습니다: " + target.getFileName());
        }
        FileChannel ch = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        StorePaths.restrictFile(target);
        ch.position(ch.size());
        durableSize = ch.size();
        return ch;
    }

    private void write(String line) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        dirty = true;
    }

    private void rollback() {
        if (channel == null) {
            return;
        }
        try {
            channel.truncate(durableSize);
            channel.position(durableSize);
            channel.force(true);
            dirty = false;
            // 되돌린 길이 기준으로 마지막 rseq·crc를 다시 맞춘다: 이 원장의 메모리 값은 강제 기록 전 줄을 반영했을 수 있다
            resyncFromDisk();
        } catch (IOException | RuntimeException e) {
            broken = true;
        }
    }

    private void resyncFromDisk() throws IOException {
        LedgerScan.Result r = day == null ? LedgerScan.single(location, stream, Instant.EPOCH)
                : LedgerScan.dated(location, stream, Instant.EPOCH);
        if (!r.ok()) {
            broken = true;
            return;
        }
        lastRseq = r.lastRseq();
        lastCrc = r.lastCrc();
        if (day != null && durableSize == 0) {
            // 오늘 파일이 비었다(체크포인트도 되돌림): 닫고, 다음 쓰기에서 직전 파일에 이어 체크포인트부터 다시 쓴다
            channel.close();
            channel = null;
            file = r.file();
            currentDay = r.file() == null ? null : LocalDate.parse(r.file().getFileName().toString().substring(0, 10));
        }
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            try {
                if (!broken) {
                    force();
                }
            } finally {
                channel.close();
                channel = null;
            }
        }
    }
}
