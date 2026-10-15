package com.autotrade.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeartbeatTest {

    @TempDir
    Path dir;

    /** 바꿀 수 있는 시계. */
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void 형식으로_쓰고_다시_읽으면_같다() {
        Heartbeat hb = new Heartbeat("engine", 1234, Instant.parse("2026-10-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:00:05Z"), 2);
        assertThat(Heartbeat.parse(hb.format())).contains(hb);
        assertThat(Heartbeat.parse(hb.format().replace("\n", "\r\n"))).contains(hb);
    }

    @Test
    void 틀린_내용은_빈_값이다() {
        assertThat(Heartbeat.parse("")).isEmpty();
        assertThat(Heartbeat.parse("process=engine\npid=x\nstarted-at=2026-10-15T00:00:00Z\nwritten-at=2026-10-15T00:00:00Z\nseq=1"))
                .isEmpty();
        assertThat(Heartbeat.parse("process=engine\npid=1\nstarted-at=어제\nwritten-at=2026-10-15T00:00:00Z\nseq=1")).isEmpty();
        assertThat(Heartbeat.parse("process=Engine\npid=1\nstarted-at=2026-10-15T00:00:00Z\nwritten-at=2026-10-15T00:00:00Z\nseq=1"))
                .isEmpty();
        assertThat(Heartbeat.parse("process=engine\nother=1")).isEmpty();
        assertThat(Heartbeat.parse("반쯤 쓴 줄")).isEmpty();
    }

    @Test
    void 파일_위치는_설정_폴더의_heartbeat_폴더이고_이름을_검사한다() {
        assertThat(Heartbeat.file(dir, "gateway")).isEqualTo(dir.resolve("heartbeat").resolve("gateway.hb"));
        assertThatThrownBy(() -> Heartbeat.file(dir, "../engine")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Heartbeat.file(dir, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기록할_때마다_시각과_순번을_갱신하고_닫으면_파일을_지운다() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
        HeartbeatWriter writer = new HeartbeatWriter(dir, "lab", clock);

        assertThat(writer.beat()).isTrue();
        clock.advance(Duration.ofSeconds(5));
        assertThat(writer.beat()).isTrue();

        Heartbeat hb = Heartbeat.parse(Files.readString(writer.file(), StandardCharsets.UTF_8)).orElseThrow();
        assertThat(hb.process()).isEqualTo("lab");
        assertThat(hb.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(hb.startedAt()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(hb.writtenAt()).isEqualTo(Instant.parse("2026-10-15T00:00:05Z"));
        assertThat(hb.seq()).isEqualTo(2);
        assertThat(Files.list(writer.file().getParent())).containsExactly(writer.file()); // 임시 파일이 남지 않음

        writer.close();
        assertThat(writer.file()).doesNotExist();
        assertThat(writer.beat()).isFalse();
        assertThat(writer.file()).doesNotExist();
    }

    @Test
    void 쓰지_못해도_던지지_않고_고쳐지면_다시_쓴다() throws Exception {
        Path blocker = dir.resolve("heartbeat");
        Files.writeString(blocker, "폴더 자리에 파일"); // 폴더를 만들 수 없게 막는다
        HeartbeatWriter writer = new HeartbeatWriter(dir, "engine", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThat(writer.beat()).isFalse();
        assertThat(writer.beat()).isFalse();

        Files.delete(blocker);
        assertThat(writer.beat()).isTrue();
        assertThat(writer.file()).exists();
        writer.close();
    }

    @Test
    void 주기_기록은_데몬_스레드에서_바로_시작한다() throws Exception {
        HeartbeatWriter writer = HeartbeatWriter.start(dir, "gateway", Clock.systemUTC(), Duration.ofMillis(20));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(writer.file()) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(writer.file()).exists();
            assertThatThrownBy(() -> writer.start(Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        } finally {
            writer.close();
        }
        assertThat(writer.file()).doesNotExist();
    }

    @Test
    void 기록기를_만들면_이전_실행이_남긴_자기_임시_파일만_거둔다() throws Exception {
        // 쓰는 도중 프로세스가 죽으면 <이름>.hb.<pid>.tmp가 남는다(PR #116 QA·architect N3)
        Path hb = Heartbeat.file(dir, "engine");
        Files.createDirectories(hb.getParent());
        Path mine = hb.resolveSibling("engine.hb.12345.tmp");
        Path otherProcess = hb.resolveSibling("gateway.hb.12345.tmp");
        Path notPid = hb.resolveSibling("engine.hb.backup.tmp");
        for (Path p : new Path[] {mine, otherProcess, notPid}) {
            Files.writeString(p, "반쯤", StandardCharsets.UTF_8);
        }

        new HeartbeatWriter(dir, "engine", Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));

        assertThat(mine).doesNotExist();
        assertThat(otherProcess).exists();
        assertThat(notPid).exists();
    }

    @Test
    void 작은_파일_읽기는_상한을_넘으면_읽지_않는다() throws Exception {
        Path small = dir.resolve("small");
        Files.writeString(small, "a".repeat((int) SmallFile.MAX_BYTES), StandardCharsets.UTF_8);
        assertThat(SmallFile.read(small)).hasSize((int) SmallFile.MAX_BYTES);

        Path big = dir.resolve("big");
        Files.writeString(big, "a".repeat((int) SmallFile.MAX_BYTES + 1), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SmallFile.read(big)).isInstanceOf(java.io.IOException.class).hasMessageContaining("상한 4096");
    }

    @Test
    void 아주_큰_파일도_상한까지만_읽어_메모리를_쓰지_않는다() throws Exception {
        // PR #118 독립 QA Q1: 파일 전체를 읽는 구현(readAllBytes)으로 바꿔도 위 시험은 통과한다. 3GB(희소 파일, 디스크는 안 씀)면
        // 전체 읽기는 배열 한도를 넘어 OutOfMemoryError가 나므로, IOException으로 바로 거절해야만 통과한다
        Path huge = dir.resolve("huge");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
            f.setLength(3L << 30);
        }
        long started = System.nanoTime();
        assertThatThrownBy(() -> SmallFile.read(huge)).isInstanceOf(java.io.IOException.class).hasMessageContaining("상한 4096");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
    }
}
