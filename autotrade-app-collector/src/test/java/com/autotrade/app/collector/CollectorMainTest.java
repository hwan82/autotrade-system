package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.daily.SnapshotListedShares;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.financial.SnapshotFinancials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 명령 전체를 가짜 서버로 돌려 스냅샷이 읽는 쪽 검사를 통과하고 키가 어떤 파일에도 남지 않는지 본다. */
class CollectorMainTest {

    @TempDir
    Path config;

    @TempDir
    Path reports;

    private static final String KRX_KEY = "KRXPLACEHOLDERAAAA";
    private static final String DART_KEY = "DARTPLACEHOLDERBBBB";

    private void writeKeys() throws IOException {
        Files.writeString(config.resolve("collector-secrets.json"),
                "{\"krx\":{\"auth-key\":\"" + KRX_KEY + "\"},\"opendart\":{\"crtfc-key\":\"" + DART_KEY + "\"}}");
    }

    private String[] args(String... cmd) {
        List<String> a = new java.util.ArrayList<>(List.of(cmd));
        a.addAll(List.of("--config-dir", config.toString(), "--report-dir", reports.toString()));
        return a.toArray(String[]::new);
    }

    private static FakeHttp krxServer() {
        return new FakeHttp((u, n) -> {
            LocalDate d = LocalDate.parse(FakeHttp.query(u, "basDd"), KrxOpenApiClient.BAS_DD);
            if (!u.getPath().endsWith("stk_bydd_trd")) {
                return FakeHttp.ok("{\"OutBlock_1\":[]}");
            }
            // 10-07에 2:1 분할(기준가 5000 = 종가 5100 − 전일 대비 100), 10-08은 변동 없음
            String row = d.isBefore(LocalDate.of(2026, 10, 7)) ? "111111;가;;9900;10100;9800;10000;0;10;100"
                    : d.equals(LocalDate.of(2026, 10, 7)) ? "111111;가;;5000;5200;4900;5100;100;20;200"
                    : "111111;가;;5100;5100;5100;5100;0;20;200";
            return FakeHttp.ok(KrxFixtures.body(d, List.of(row)));
        });
    }

    @Test
    void krx_daily는_일봉_스냅샷과_품질_리포트를_만든다() throws IOException {
        writeKeys();
        // 서울 2026-10-08 19:00
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        int code = CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08"), clock, http, ms -> {});
        assertThat(code).isZero();
        assertThat(http.calls).hasSize(12);

        Path root = config.resolve("snapshots").resolve("krx-daily");
        List<Path> dirs;
        try (Stream<Path> s = Files.list(root)) {
            dirs = s.toList();
        }
        assertThat(dirs).hasSize(1);
        Path dir = dirs.get(0);
        assertThat(dir.getFileName().toString()).startsWith("krx-daily-2026-10-08-");
        SnapshotDailyBarSource src = SnapshotDailyBarSource.load(dir);
        SnapshotManifest m = src.manifest();
        assertThat(m.coverage().lastTradingDate()).isEqualTo("2026-10-08");
        assertThat(m.coverage().sharesAsOf()).isEqualTo("2026-10-08");
        assertThat(m.coverage().firstDate()).isEqualTo("2026-10-05");
        assertThat(m.sources().get(0).license()).contains("비상업", "한국거래소 통계정보");
        Symbol s = new Symbol(Market.KRX, "111111");
        assertThat(src.bars(s, LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6)).get(0).adjustedClose())
                .isEqualByComparingTo("5000");
        assertThat(src.qualityReport().errorCount()).isZero();
        SnapshotListedShares shares = SnapshotListedShares.load(dir);
        assertThat(shares.asOf(s, LocalDate.of(2026, 10, 6))).hasValue(100);
        assertThat(shares.asOf(s, LocalDate.of(2026, 10, 9))).hasValue(200);
        assertThat(shares.asOf(s, LocalDate.of(2026, 10, 4))).isEmpty();

        Path report = reports.resolve(m.snapshotId() + ".md");
        assertThat(Files.readString(report)).contains("## 수집 요약", "| 수정 이벤트(기준가 ≠ 전일 종가) | 1 |");

        // 같은 날 다시 돌리면 오늘(10-08)만 다시 받고 같은 스냅샷 ID가 나온다
        FakeHttp again = krxServer();
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08"), clock, again, ms -> {}))
                .isZero();
        assertThat(again.calls).hasSize(3);
        try (Stream<Path> st = Files.list(root)) {
            assertThat(st.toList()).hasSize(1);
        }
        assertNoKeyAnywhere();
    }

    @Test
    void dart_financials는_재무_스냅샷과_품질_리포트를_만든다() throws IOException {
        writeKeys();
        FakeHttp http = new FakeHttp(DartFinancialCollectorTest.server(false));
        Clock clock = Clock.fixed(Instant.parse("2026-03-20T03:00:00Z"), ZoneOffset.UTC);
        int code = CollectorMain.run(args("dart-financials", "--from", "2026-03-01", "--dart-base-url", "https://dart.test/api"),
                clock, http, ms -> {});
        assertThat(code).isZero();
        Path root = config.resolve("snapshots").resolve("dart-financials");
        Path dir;
        try (Stream<Path> s = Files.list(root)) {
            dir = s.findFirst().orElseThrow();
        }
        SnapshotFinancials f = SnapshotFinancials.load(dir);
        assertThat(f.filings()).hasSize(3); // 대기 1건은 뺀다
        assertThat(f.manifest().coverage().lastCollectedAt()).isEqualTo("2026-03-20T03:00:00Z");
        assertThat(f.manifest().snapshotId()).startsWith("dart-financials-2026-03-19-");
        String report = Files.readString(reports.resolve(f.manifest().snapshotId() + ".md"));
        assertThat(report).contains("대체 규칙 대상 묶음", "| 계정 응답 대기(반영 지연, 스냅샷에서 뺌) | 1 |");
        assertNoKeyAnywhere();
    }

    @Test
    void 키가_없거나_사용법이_틀리면_멈춘다() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        assertThat(CollectorMain.run(args("krx-daily"), clock, http, ms -> {})).isEqualTo(1);
        assertThat(CollectorMain.run(new String[] {"recordings"}, clock, http, ms -> {})).isEqualTo(2);
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-09", "--to", "2026-10-01"), clock, http, ms -> {}))
                .isEqualTo(2);
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 호출_상한에_닿으면_종료_코드_3() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08", "--max-calls", "2"),
                clock, krxServer(), ms -> {})).isEqualTo(3);
    }

    /** 캐시·스냅샷·리포트 어디에도 키 평문이 없다(MD-11). 키 파일 자체만 예외. */
    private void assertNoKeyAnywhere() throws IOException {
        for (Path base : List.of(config, reports)) {
            try (Stream<Path> files = Files.walk(base)) {
                for (Path p : files.filter(Files::isRegularFile).toList()) {
                    if (p.getFileName().toString().equals("collector-secrets.json")) {
                        continue;
                    }
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    assertThat(text).as(p.toString()).doesNotContain(KRX_KEY, DART_KEY);
                }
            }
        }
    }
}
