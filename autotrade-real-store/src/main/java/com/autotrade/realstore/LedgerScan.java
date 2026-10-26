package com.autotrade.realstore;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 원장 검사(ADR-015 2.7 ③). 체크포인트(날짜 원장의 첫 줄)부터 {@code rseq}·{@code crc}를 대조하고, 끝이 잘린 마지막 줄만 따로
 * 옮겨 잘라 낸다. 손상(중간 줄 CRC 불일치, {@code rseq} 건너뜀·되돌아감, 모르는 버전, 첫 줄이 체크포인트가 아님, 직전 날짜 파일과
 * 이어지지 않음)이 있으면 파일을 그대로 두고 손상으로 알린다. 한 원장에서 큰 부팅 번호 뒤에 작은 부팅 번호의 기록이 이어지면
 * "쓰기 주체 둘"이다(2.9 (나)).
 */
final class LedgerScan {

    /** 날짜 원장 파일 이름(거래소 현지 거래일). */
    static final Pattern DATED_FILE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.jsonl");
    static final String CHECKPOINT = "checkpoint";

    enum Status {
        OK,
        CORRUPT,
        TWO_WRITERS
    }

    /**
     * @param file 이어 쓸 파일(날짜 원장이면 내용이 있는 가장 최근 날짜 파일). 원장이 아직 없으면 null
     * @param lastRseq 마지막 기록의 {@code rseq}(없으면 0)
     * @param lastCrc 마지막 기록의 {@code crc}(없으면 null)
     * @param maxBoot 읽은 기록의 가장 큰 부팅 번호(없으면 0)
     * @param entries 읽은 기록(날짜 원장이면 가장 최근 파일만)
     * @param cut 잘라 낸 끝 줄을 옮긴 파일(없으면 null)
     */
    record Result(String stream, Path file, Status status, String problem, long lastRseq, String lastCrc, long maxBoot,
            List<LedgerEntry> entries, Path cut) {

        boolean ok() {
            return status == Status.OK;
        }

        Optional<LedgerEntry> last() {
            return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1));
        }
    }

    private LedgerScan() {}

    /** 한 파일짜리 원장({@code boot/boot.jsonl}, {@code risk/risk.jsonl}, {@code blacklist/blacklist.jsonl}). 첫 줄 {@code rseq}는 1. */
    static Result single(Path file, String stream, Instant now) throws IOException {
        if (Files.isSymbolicLink(file)) {
            return corrupt(stream, file, "원장 파일이 심볼릭 링크", 0);
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Result(stream, null, Status.OK, null, 0, null, 0, List.of(), null);
        }
        return scanFile(file, stream, false, now);
    }

    /** 날짜 원장({@code <날짜>.jsonl} 여러 개, 첫 줄은 체크포인트). 가장 최근 파일과 그 직전 파일의 연결을 본다. */
    static Result dated(Path dir, String stream, Instant now) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            return corrupt(stream, dir, "원장 폴더가 심볼릭 링크", 0);
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new Result(stream, null, Status.OK, null, 0, null, 0, List.of(), null);
        }
        List<Path> files = datedFiles(dir);
        // 만든 직후 체크포인트를 쓰기 전에 꺼진 빈 파일은 기록이 없다. 이어 쓸 때 체크포인트부터 다시 쓴다(LedgerFile)
        while (!files.isEmpty() && !Files.isSymbolicLink(files.get(files.size() - 1))
                && Files.size(files.get(files.size() - 1)) == 0) {
            files.remove(files.size() - 1);
        }
        if (files.isEmpty()) {
            return new Result(stream, null, Status.OK, null, 0, null, 0, List.of(), null);
        }
        Path latest = files.get(files.size() - 1);
        Result r = scanFile(latest, stream, true, now);
        if (!r.ok() || r.entries().isEmpty()) {
            // 체크포인트 줄까지 잘려 나간 파일은 빈 파일과 같다. 직전 파일을 이어 쓸 기준으로 다시 본다
            if (r.ok() && files.size() > 1) {
                files.remove(files.size() - 1);
                Path previous = files.get(files.size() - 1);
                Result p = scanFile(previous, stream, true, now);
                return p.ok() ? new Result(stream, previous, Status.OK, null, p.lastRseq(), p.lastCrc(), p.maxBoot(), p.entries(),
                        r.cut()) : p;
            }
            return r;
        }
        JsonNode checkpoint = r.entries().get(0).body();
        JsonNode prevFile = checkpoint.get("prevFile");
        if (prevFile == null || prevFile.isNull()) {
            if (files.size() > 1 && checkpoint.get("restartAfter") == null) {
                return corrupt(stream, latest, "체크포인트에 직전 파일 연결이 없음", r.maxBoot());
            }
            return r;
        }
        Path previous = dir.resolve(prevFile.asText());
        if (!previous.getParent().equals(dir) || !DATED_FILE.matcher(previous.getFileName().toString()).matches()
                || Files.isSymbolicLink(previous) || !Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
            return corrupt(stream, latest, "체크포인트의 직전 파일이 없음", r.maxBoot());
        }
        Optional<LedgerEntry> prevLast = lastComplete(previous);
        if (prevLast.isEmpty() || prevLast.get().rseq() != checkpoint.path("prevRseq").asLong(-1)
                || !prevLast.get().crc().equals(checkpoint.path("prevCrc").asText())) {
            return corrupt(stream, latest, "직전 날짜 파일의 마지막 기록과 체크포인트가 이어지지 않음", r.maxBoot());
        }
        if (prevLast.get().boot() > r.entries().get(0).boot()) {
            return new Result(stream, latest, Status.TWO_WRITERS, "직전 날짜 파일보다 작은 부팅 번호로 이어 씀", r.lastRseq(),
                    r.lastCrc(), Math.max(r.maxBoot(), prevLast.get().boot()), r.entries(), r.cut());
        }
        return r;
    }

    static List<Path> datedFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> DATED_FILE.matcher(p.getFileName().toString()).matches()).sorted().forEach(files::add);
        }
        return files;
    }

    private static Result scanFile(Path file, String stream, boolean dated, Instant now) throws IOException {
        if (Files.isSymbolicLink(file)) {
            return corrupt(stream, file, "원장 파일이 심볼릭 링크", 0);
        }
        byte[] bytes = readAll(file);
        int end = lastNewline(bytes);
        List<LedgerEntry> entries = new ArrayList<>();
        long expected = -1;
        long maxBoot = 0;
        int start = 0;
        int lineNo = 0;
        while (start < end) {
            int nl = indexOf(bytes, start, end);
            String line = new String(bytes, start, nl - start, StandardCharsets.UTF_8);
            start = nl + 1;
            lineNo++;
            LedgerEntry e;
            try {
                e = LedgerCodec.decode(line);
            } catch (LedgerCodec.Corrupt c) {
                return corrupt(stream, file, lineNo + "번째 줄 " + c.getMessage(), maxBoot);
            }
            if (!e.stream().equals(stream)) {
                return corrupt(stream, file, lineNo + "번째 줄 원장 이름이 다름", maxBoot);
            }
            if (expected < 0) {
                if (dated) {
                    if (!CHECKPOINT.equals(e.type())) {
                        return corrupt(stream, file, "첫 줄이 체크포인트가 아님", maxBoot);
                    }
                    expected = e.body().path("prevRseq").asLong(0) + 1;
                } else {
                    expected = 1;
                }
            }
            if (e.rseq() != expected) {
                return corrupt(stream, file, lineNo + "번째 줄 rseq " + e.rseq() + "(기대 " + expected + ")", maxBoot);
            }
            if (e.boot() < maxBoot) {
                return new Result(stream, file, Status.TWO_WRITERS, lineNo + "번째 줄 부팅 번호 " + e.boot() + "가 앞선 " + maxBoot
                        + " 뒤에 이어짐", 0, null, maxBoot, entries, null);
            }
            maxBoot = e.boot();
            expected++;
            entries.add(e);
        }
        Path cut = null;
        int tail = bytes.length - (end < 0 ? 0 : end + 1);
        if (tail > 0) {
            cut = cutTail(file, bytes, end < 0 ? 0 : end + 1, now);
        }
        LedgerEntry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        return new Result(stream, file, Status.OK, null, last == null ? 0 : last.rseq(), last == null ? null : last.crc(),
                maxBoot, List.copyOf(entries), cut);
    }

    /**
     * 끝이 잘린 마지막 줄(줄바꿈 없음)은 "보내지 않은 기록"이다(원칙 3). 그 바이트를 별도 파일로 옮겨 두고 원장에서 잘라 낸다(2.4).
     * 지우지 않고 옮기므로 사람이 볼 수 있다.
     */
    private static Path cutTail(Path file, byte[] bytes, int keep, Instant now) throws IOException {
        Path cut = file.resolveSibling(file.getFileName() + ".cut-" + now.toEpochMilli());
        int n = 0;
        while (Files.exists(cut, LinkOption.NOFOLLOW_LINKS)) {
            cut = file.resolveSibling(file.getFileName() + ".cut-" + now.toEpochMilli() + "-" + (++n));
        }
        try (FileChannel out = FileChannel.open(cut, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes, keep, bytes.length - keep);
            while (buf.hasRemaining()) {
                out.write(buf);
            }
            out.force(true);
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ch.truncate(keep);
            ch.force(true);
        }
        return cut;
    }

    /** 파일의 마지막 완전한 줄을 읽는다(직전 날짜 파일 연결 확인용). */
    private static Optional<LedgerEntry> lastComplete(Path file) throws IOException {
        byte[] bytes = readAll(file);
        int end = lastNewline(bytes);
        if (end < 0) {
            return Optional.empty();
        }
        int start = end - 1;
        while (start >= 0 && bytes[start] != '\n') {
            start--;
        }
        try {
            return Optional.of(LedgerCodec.decode(new String(bytes, start + 1, end - start - 1, StandardCharsets.UTF_8)));
        } catch (LedgerCodec.Corrupt e) {
            return Optional.empty();
        }
    }

    static byte[] readAll(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = ch.size();
            if (size > Integer.MAX_VALUE - 16) {
                throw new IOException("원장 파일이 너무 큽니다: " + file.getFileName());
            }
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
                // 끝까지 읽는다
            }
            return buf.array();
        }
    }

    private static int lastNewline(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] bytes, int from, int end) {
        for (int i = from; i < end; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return end;
    }

    private static Result corrupt(String stream, Path file, String problem, long maxBoot) {
        return new Result(stream, file, Status.CORRUPT, problem, 0, null, maxBoot, List.of(), null);
    }
}
