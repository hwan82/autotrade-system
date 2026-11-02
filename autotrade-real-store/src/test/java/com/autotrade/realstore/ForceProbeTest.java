package com.autotrade.realstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 운영 PC 강제 기록 실측 도구(ADR-015 3장 되돌릴 조건 (1)). */
class ForceProbeTest {

    @TempDir
    Path dir;

    @Test
    void 백분위를_계산하고_임시_파일을_남기지_않는다() throws Exception {
        // 가짜 단조 시계: i번째 샘플(0부터)이 (i + 1)ms 걸린 것처럼 만든다
        AtomicLong t = new AtomicLong();
        AtomicLong calls = new AtomicLong();
        ForceProbe.Result r = ForceProbe.run(dir, 100, () -> {
            long n = calls.getAndIncrement();
            // 시작 호출(짝수)은 그대로, 끝 호출(홀수)은 (샘플 번호 + 1)ms를 더한다
            return n % 2 == 0 ? t.get() : t.addAndGet((n / 2 + 1) * 1_000_000L);
        }, Instant.parse("2026-10-26T01:00:00Z"));
        assertThat(r.samples()).isEqualTo(100);
        assertThat(r.p50Micros()).isEqualTo(50_000);
        assertThat(r.p95Micros()).isEqualTo(95_000);
        assertThat(r.p99Micros()).isEqualTo(99_000);
        assertThat(r.maxMicros()).isEqualTo(100_000);
        assertThat(r.line()).contains("중앙값 50.00ms").contains("최대 100.00ms");
        try (Stream<Path> left = Files.list(dir)) {
            assertThat(left).isEmpty();
        }
    }
}
