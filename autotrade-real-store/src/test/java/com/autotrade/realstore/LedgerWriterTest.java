package com.autotrade.realstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 쓰기 스레드의 묶음 기록(ADR-015 2.4). */
class LedgerWriterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    /** 직렬화하면 예외를 던지는 값(붙이기 실패 흉내). */
    public static final class Exploding {
        public String getValue() {
            throw new IllegalStateException("직렬화 실패 흉내");
        }
    }

    private static ObjectNode body(String reason) {
        ObjectNode b = LedgerCodec.body();
        b.put("reason", reason);
        return b;
    }

    @Test
    void 같은_묶음의_뒤_줄_붙이기가_실패하면_되돌려진_앞_줄도_기록됨으로_끝나지_않는다() throws Exception {
        Path file = dir.resolve("audit.jsonl");
        LedgerFile ledger = LedgerFile.single(file, LedgerScan.single(file, "test/audit", CLOCK.instant()));
        LedgerWriter writer = new LedgerWriter("test", 1, CLOCK, 100, (l, e) -> { });
        try {
            CountDownLatch gate = new CountDownLatch(1);
            writer.gateForce(gate);
            // 앞선 줄이 강제 기록 앞에서 멈춘 동안 A·B를 쌓아 다음 묶음 하나로 만든다
            LedgerWriter.Pending first = writer.submit(ledger, "note", body("앞선 줄"));
            awaitBacklog(writer, 0);
            LedgerWriter.Pending a = writer.submit(ledger, "note", body("A 올림"));
            ObjectNode bad = body("B");
            bad.putPOJO("explode", new Exploding());
            LedgerWriter.Pending b = writer.submit(ledger, "note", bad);
            writer.gateForce(null);
            gate.countDown();
            assertThat(first.done.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(failed(b)).isTrue();
            assertThat(failed(a)).as("되돌려진 줄은 실패로 돌려준다").isTrue();
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("앞선 줄").doesNotContain("A 올림");
            // 되돌린 뒤 이어 쓰는 줄은 rseq가 겹치거나 건너뛰지 않는다
            LedgerWriter.Pending next = writer.submit(ledger, "note", body("다음"));
            assertThat(next.done.get(5, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(LedgerScan.single(file, "test/audit", CLOCK.instant()).ok()).isTrue();
        } finally {
            writer.close();
        }
    }

    private static boolean failed(LedgerWriter.Pending p) throws InterruptedException {
        try {
            p.done.get(5, TimeUnit.SECONDS);
            return false;
        } catch (ExecutionException e) {
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("기록 결과가 오지 않음", e);
        }
    }

    private static void awaitBacklog(LedgerWriter writer, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (writer.backlog() != size && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        Thread.sleep(50); // 쓰기 스레드가 앞선 줄을 붙이고 강제 기록 문 앞에 설 시간
    }
}
