package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 실제 규모에서 기본 배치 뒷단계가 힙 상한 안에서 끝나는지(QA PR #39 F1). 배포본 상한은 2GB이고 예전 방식은 약 300만 행부터
 * 메모리 부족이었다(QA 실측). 테스트 시간을 줄이려고 힙을 128MB로 줄이고 120만 행(1,000종목 × 1,200일)을 쓴다: 예전 방식은
 * 이 힙에서 메모리 부족이고(비교 실행으로 확인), 날짜 순 검사는 끝까지 돌아 옛 스냅샷 정리와 리포트를 남긴다.
 */
class CollectorScaleTest {

    static final String HEAP = "-Xmx128m";
    static final int CODES = 1_000;
    static final int DAYS = 1_200;

    @TempDir
    Path config;

    @TempDir
    Path experiments;

    private int probe(String mode, Path log) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), HEAP,
                "-cp", System.getProperty("java.class.path"), KrxScaleProbe.class.getName(), mode, config.toString(),
                experiments.toString(), Integer.toString(CODES), Integer.toString(DAYS)));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(p.waitFor(5, TimeUnit.MINUTES)).as("시간 안에 끝남").isTrue();
        return p.exitValue();
    }

    @Test
    void 전_기간_규모에서도_정리와_품질_리포트가_힙_상한_안에서_끝난다() throws Exception {
        Path root = Files.createDirectories(config.resolve("snapshots").resolve("krx-daily"));
        // 지워질 옛 스냅샷 하나
        Path old = Files.createDirectories(root.resolve("krx-daily-2026-10-01-aaaaaaaa"));
        Files.writeString(old.resolve("manifest.json"), "{\"snapshotId\":\"krx-daily-2026-10-01-aaaaaaaa\",\"schemaVersion\":1,"
                + "\"createdAt\":\"2026-10-01T10:00:00Z\",\"scriptVersion\":\"0.1.0\",\"sources\":[],\"files\":[]}");
        Path log = config.resolve("probe.log");

        int code = probe("collector", log);
        assertThat(code).as(Files.readString(log, StandardCharsets.UTF_8)).isZero();
        assertThat(old).doesNotExist();
        List<Path> reports;
        try (Stream<Path> s = Files.list(experiments.resolve("_data-quality"))) {
            reports = s.toList();
        }
        assertThat(reports).hasSize(1);
        assertThat(Files.readString(reports.get(0))).contains("검사한 일봉: " + (long) CODES * DAYS + "건");

        // 비교: 같은 힙에서 예전 방식(전체 적재)은 메모리 부족으로 끝난다
        Path log2 = config.resolve("probe-full.log");
        assertThat(probe("full-load", log2)).isNotZero();
        assertThat(Files.readString(log2, StandardCharsets.UTF_8)).contains("OutOfMemoryError");
    }
}
