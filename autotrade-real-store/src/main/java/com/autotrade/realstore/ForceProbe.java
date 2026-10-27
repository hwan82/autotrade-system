package com.autotrade.realstore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * 운영 PC 강제 기록 실측(ADR-015 2.5·3장 되돌릴 조건 (1), B1). 원장과 같은 크기(약 300바이트)의 줄을 붙이고
 * {@code FileChannel.force(true)}까지 걸린 시간을 여러 번 재어 분포를 돌려준다. 원장 폴더가 아니라 그 옆 임시 파일에 쓰고 끝나면
 * 그 임시 파일만 지운다. 실전 엔진의 {@code store-probe} 인자로 사람이 운영 PC에서 돌린다(엔진이 꺼져 있을 때).
 */
public final class ForceProbe {

    /**
     * 실측 결과(마이크로초).
     *
     * @param samples 잰 횟수
     */
    public record Result(int samples, long p50Micros, long p95Micros, long p99Micros, long maxMicros) {

        /** 사람이 읽는 한 줄. */
        public String line() {
            return "강제 기록 " + samples + "회: 중앙값 " + ms(p50Micros) + ", 95% " + ms(p95Micros) + ", 99% " + ms(p99Micros)
                    + ", 최대 " + ms(maxMicros);
        }

        private static String ms(long micros) {
            return (micros / 1000) + "." + String.format("%02d", (micros % 1000) / 10) + "ms";
        }
    }

    private ForceProbe() {}

    /**
     * {@code dir} 안에 임시 파일을 만들어 {@code samples}번 잰다.
     *
     * @param nanos 단조 시계(시험에서 바꿔 끼움)
     */
    public static Result run(Path dir, int samples, LongSupplier nanos, Instant now) throws IOException {
        if (samples < 1) {
            throw new IllegalArgumentException("샘플 수는 1 이상이어야 합니다");
        }
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("실측 폴더가 심볼릭 링크라 쓰지 않습니다: " + dir.getFileName());
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("force-probe-" + now.toEpochMilli() + ".tmp");
        long[] took = new long[samples];
        byte[] line = ("{\"v\":1,\"stream\":\"probe\",\"rseq\":0,\"boot\":0,\"at\":\"" + now + "\",\"type\":\"probe\",\"body\":{\"pad\":\""
                + "x".repeat(180) + "\"},\"crc\":\"00000000\"}\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            for (int i = 0; i < samples; i++) {
                long start = nanos.getAsLong();
                ByteBuffer buf = ByteBuffer.wrap(line);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
                took[i] = (nanos.getAsLong() - start) / 1000;
            }
        } finally {
            Files.deleteIfExists(file);
        }
        Arrays.sort(took);
        return new Result(samples, pick(took, 50), pick(took, 95), pick(took, 99), took[samples - 1]);
    }

    /** 백분위(올림 순위). */
    private static long pick(long[] sorted, int percent) {
        int i = (percent * sorted.length + 99) / 100 - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }
}
