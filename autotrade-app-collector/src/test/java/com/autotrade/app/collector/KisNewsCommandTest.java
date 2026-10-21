package com.autotrade.app.collector;

import static com.autotrade.app.collector.KisNewsFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.marketdata.daily.Csv;
import com.autotrade.marketdata.daily.SnapshotFiles;
import com.autotrade.marketdata.daily.SnapshotManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code kis-news} 명령 끝까지(D-136): 키 파일 → 수집 → 스냅샷·품질 리포트. */
class KisNewsCommandTest {

    @TempDir
    Path config;

    @TempDir
    Path reports;

    private static final String APP_KEY = "KISKEYCCCCCCCCCC";
    private static final String APP_SECRET = "KISSECRETDDDDDDDD";
    /** 서울 2026-10-21 07:00(수, 국내 장 보호 08:00 전, 미국 장 보호 05:30 뒤). */
    private final Clock morning = KisNewsCollectorTest.seoul("2026-10-21T07:00:00");

    private void writeKeys() throws IOException {
        Files.writeString(config.resolve("collector-secrets.json"),
                "{\"kis-news\":{\"app-key\":\"" + APP_KEY + "\",\"app-secret\":\"" + APP_SECRET + "\"}}");
    }

    private String[] args(String... cmd) {
        List<String> a = new java.util.ArrayList<>(List.of(cmd));
        a.addAll(List.of("--config-dir", config.toString(), "--report-dir", reports.toString()));
        if (!a.contains("--kis-base-url")) {
            a.addAll(List.of("--kis-base-url", "https://kis.test"));
        }
        return a.toArray(String[]::new);
    }

    private static FakeHttp server() {
        return new FakeHttp(KisNewsFixtures.server(List.of(row("20261021", "063000", "N1", "005930;000660"),
                row("20261021", "062000", "N2", ""), row("20261021", "061000", "N3", "035420"),
                row("20261020", "235000", "N0", "005930")), 2));
    }

    private int run(Clock clock, FakeHttp http, String... cmd) {
        return CollectorMain.run(args(cmd), clock, http, ms -> {}, CollectorMainTest.PLENTY);
    }

    @Test
    void 종목코드_붙은_제목과_훑은_구간으로_스냅샷을_만들고_리포트에는_제목이_없다() throws IOException {
        writeKeys();
        PrintStream old = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = run(morning, server(), "kis-news");
        } finally {
            System.setOut(old);
        }
        assertThat(code).isZero();
        String console = out.toString(StandardCharsets.UTF_8);
        assertThat(console).contains("접근토큰 발급 1번").doesNotContain("KISKEY").doesNotContain("KISSECRET");

        Path root = config.resolve("snapshots").resolve("kis-news");
        Path dir;
        try (Stream<Path> s = Files.list(root)) {
            dir = s.filter(p -> p.getFileName().toString().startsWith("kis-news-2026-10-21-")).findFirst().orElseThrow();
        }
        SnapshotManifest m = SnapshotFiles.readManifest(dir);
        assertThat(m.sources().getFirst().name()).contains("FHKST01011800").contains("kis-news-title-iscd@1");
        List<List<String>> rows = SnapshotFiles.readVerified(dir, m, KisNewsSnapshotBuilder.FILE, KisNewsSnapshotBuilder.HEADER);
        // 구간(10-21 00:00~07:00) 안의 종목코드 붙은 제목만, 시각 순. N2(태그 없음)와 N0(구간 밖) 제외
        assertThat(rows).extracting(r -> r.get(1)).containsExactly("N3", "N1");
        assertThat(rows.get(1).get(0)).isEqualTo("2026-10-21T06:30:00");
        assertThat(rows.get(1).get(5)).isEqualTo("005930;000660");
        List<List<String>> cov = SnapshotFiles.readVerified(dir, m, KisNewsSnapshotBuilder.COVERAGE,
                KisNewsSnapshotBuilder.COVERAGE_HEADER);
        assertThat(cov).containsExactly(List.of("2026-10-21T00:00:00", "2026-10-21T07:00:00"));

        Path report = reports.resolve(m.snapshotId() + ".md");
        String text = Files.readString(report);
        assertThat(text).contains("훑은 구간").contains("| 2026-10-21 | 3 | 2 |").doesNotContain("합성 제목").doesNotContain("N1");
        assertThat(Csv.read(KisNewsCollector.dayFile(config.resolve("collector-cache").resolve("kis-news"),
                java.time.LocalDate.of(2026, 10, 21)), KisNewsCollector.DAY_HEADER)).hasSize(3);
    }

    @Test
    void 같은_캐시와_구간이면_같은_ID다() throws IOException {
        writeKeys();
        assertThat(run(morning, server(), "kis-news")).isZero();
        Path root = config.resolve("snapshots").resolve("kis-news");
        List<String> first;
        try (Stream<Path> s = Files.list(root)) {
            first = s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("kis-news-")).toList();
        }
        // 같은 시각에 다시: 새 제목 없음, 구간 같음 → 같은 ID 폴더(덮어쓰지 않음)
        assertThat(run(morning, server(), "kis-news")).isZero();
        try (Stream<Path> s = Files.list(root)) {
            assertThat(s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("kis-news-")).toList())
                    .isEqualTo(first);
        }
    }

    @Test
    void 장_시간_보호_구간에는_시작하지_않는다() throws IOException {
        writeKeys();
        FakeHttp http = server();
        assertThat(run(KisNewsCollectorTest.seoul("2026-10-21T09:30:00"), http, "kis-news")).isEqualTo(4);
        assertThat(http.calls).isEmpty();
        assertThat(http.posts).isEmpty();
    }

    @Test
    void 키가_없으면_오류이고_아무것도_부르지_않는다() {
        FakeHttp http = server();
        assertThat(run(morning, http, "kis-news")).isEqualTo(1);
        assertThat(http.calls).isEmpty();
        assertThat(http.posts).isEmpty();
    }

    @Test
    void 옵션_검사() {
        assertThat(run(morning, server(), "kis-news", "--to", "2026-10-21")).isEqualTo(2);
        assertThat(run(morning, server(), "kis-news", "--interval-ms", "50")).isEqualTo(2);
        assertThat(run(morning, server(), "kis-news", "--max-calls", "2001")).isEqualTo(2);
        assertThat(run(morning, server(), "kis-news", "--types", "B")).isEqualTo(2);
        assertThat(run(morning, server(), "kis-news", "--kis-base-url", "http://kis.test")).isEqualTo(2);
    }

    @Test
    void 기본_간격은_350ms다() throws IOException {
        writeKeys();
        List<Long> waits = new java.util.ArrayList<>();
        CollectorMain.run(args("kis-news"), morning, server(), waits::add, CollectorMainTest.PLENTY);
        assertThat(waits).isNotEmpty().allMatch(w -> w == CollectorMain.KIS_NEWS_INTERVAL_MS);
    }
}
