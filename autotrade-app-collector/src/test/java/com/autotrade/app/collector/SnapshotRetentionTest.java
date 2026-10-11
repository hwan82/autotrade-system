package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotRetentionTest {

    @TempDir
    Path root;

    @TempDir
    Path experiments;

    private Path snapshot(String id, String createdAt) throws IOException {
        Path d = Files.createDirectories(root.resolve(id));
        Files.writeString(d.resolve("manifest.json"), "{\"snapshotId\":\"" + id + "\",\"schemaVersion\":1,\"createdAt\":\""
                + createdAt + "\",\"scriptVersion\":\"0.1.0\",\"sources\":[],\"files\":[]}");
        Files.writeString(d.resolve("daily_bars.csv"), "x\n");
        return d;
    }

    @Test
    void 최근_K개와_실험이_참조하는_스냅샷만_남긴다() throws IOException {
        Path a = snapshot("krx-daily-2026-10-01-aaaaaaaa", "2026-10-01T10:00:00Z");
        Path b = snapshot("krx-daily-2026-10-02-bbbbbbbb", "2026-10-02T10:00:00Z");
        Path c = snapshot("krx-daily-2026-10-05-cccccccc", "2026-10-05T10:00:00Z");
        Path d = snapshot("krx-daily-2026-10-06-dddddddd", "2026-10-06T10:00:00Z");
        Path other = snapshot("dart-financials-2026-10-01-eeeeeeee", "2026-10-01T10:00:00Z");
        Files.createDirectories(experiments.resolve("EXP-001"));
        Files.writeString(experiments.resolve("EXP-001").resolve("config.yaml"), "snapshot: krx-daily-2026-10-01-aaaaaaaa\n");

        List<Path> removed = SnapshotRetention.prune(root, "krx-daily", 2, d, experiments, experiments.resolve("_data-quality"));

        assertThat(removed).containsExactly(b);
        assertThat(a).exists(); // 실험 기록이 참조
        assertThat(c).exists();
        assertThat(d).exists();
        assertThat(other).exists(); // 다른 소스는 건드리지 않는다
    }

    @Test
    void 품질_리포트는_참조로_보지_않고_실험_폴더가_없으면_지우지_않는다() throws IOException {
        Path a = snapshot("krx-daily-2026-10-01-aaaaaaaa", "2026-10-01T10:00:00Z");
        Path b = snapshot("krx-daily-2026-10-02-bbbbbbbb", "2026-10-02T10:00:00Z");
        Path reports = Files.createDirectories(experiments.resolve("_data-quality"));
        Files.writeString(reports.resolve("krx-daily-2026-10-01-aaaaaaaa.md"), "# 품질 리포트 krx-daily-2026-10-01-aaaaaaaa\n");

        assertThat(SnapshotRetention.prune(root, "krx-daily", 1, b, experiments.resolve("missing"), reports)).isEmpty();
        assertThat(a).exists();

        assertThat(SnapshotRetention.prune(root, "krx-daily", 1, b, experiments, reports)).containsExactly(a);
        assertThat(b).exists();
    }

    @Test
    void 방금_만든_스냅샷은_K_밖이어도_남긴다() throws IOException {
        Path old = snapshot("krx-daily-2026-10-01-aaaaaaaa", "2026-10-01T10:00:00Z");
        Path newer = snapshot("krx-daily-2026-10-02-bbbbbbbb", "2026-10-09T10:00:00Z");
        // 같은 내용이라 기존 폴더를 돌려받은 경우(생성 시각이 옛 것)
        List<Path> removed = SnapshotRetention.prune(root, "krx-daily", 1, old, experiments, experiments.resolve("_data-quality"));
        assertThat(removed).isEmpty();
        assertThat(newer).exists();
    }
}
