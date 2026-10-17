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

    /** 디스크 여유는 고정값으로(CI 러너 디스크와 무관하게). */
    static final RunGuard.FreeSpace PLENTY = dir -> 1L << 40;

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
        int code = CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08"), clock, http, ms -> {}, PLENTY);
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
        assertThat(Files.readString(report)).contains("## 수집 요약", "| 수정 이벤트(기준가 ≠ 전일 종가) | 1 |",
                "## 빈 평일(모든 시장 빈 목록)", "## 시장 하나만 빈 날", "## 같은 날 여러 종목 수정 이벤트(경고)");

        // 같은 날 다시 돌리면 오늘(10-08)과 최근 평일 빈 응답(코스닥·코넥스 10-05~10-07, 게시 전일 수 있음)만 다시 받고
        // 같은 스냅샷 ID가 나온다
        FakeHttp again = krxServer();
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08"), clock, again, ms -> {}, PLENTY))
                .isZero();
        assertThat(again.calls).hasSize(3 + 6);
        try (Stream<Path> st = Files.list(root)) {
            assertThat(st.toList()).hasSize(1);
        }
        assertNoKeyAnywhere();
    }

    @Test
    void dart_financials는_재무_스냅샷과_품질_리포트를_만든다() throws IOException {
        writeKeys();
        FakeHttp http = new FakeHttp(DartFinancialCollectorTest.server(false));
        // 서울 2026-03-20 19:00(장 시간 보호 구간 밖)
        Clock clock = Clock.fixed(Instant.parse("2026-03-20T10:00:00Z"), ZoneOffset.UTC);
        int code = CollectorMain.run(args("dart-financials", "--from", "2026-03-01", "--dart-base-url", "https://dart.test/api"),
                clock, http, ms -> {}, PLENTY);
        assertThat(code).isZero();
        Path root = config.resolve("snapshots").resolve("dart-financials");
        Path dir;
        try (Stream<Path> s = Files.list(root)) {
            dir = s.findFirst().orElseThrow();
        }
        SnapshotFinancials f = SnapshotFinancials.load(dir);
        assertThat(f.filings()).hasSize(3); // 대기 1건은 뺀다
        assertThat(f.manifest().coverage().lastCollectedAt()).isEqualTo("2026-03-20T10:00:00Z");
        assertThat(f.manifest().snapshotId()).startsWith("dart-financials-2026-03-19-");
        String report = Files.readString(reports.resolve(f.manifest().snapshotId() + ".md"));
        assertThat(report).contains("대체 규칙 대상 묶음", "| 계정 응답 대기(반영 지연, 스냅샷에서 뺌) | 1 |");
        assertNoKeyAnywhere();
    }

    @Test
    void 캘린더상_거래일에_KRX가_빈_목록을_주면_종료_코드_1로_멈추고_그날을_캐시하지_않는다() throws IOException {
        writeKeys();
        // 서울 2026-10-08 19:00. 2026-09-22(화)는 번들 캘린더상 거래일이고 7일보다 오래됐다
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = new FakeHttp((u, n) -> FakeHttp.ok("{\"OutBlock_1\":[]}"));
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.err;
        int code;
        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            code = CollectorMain.run(args("krx-daily", "--from", "2026-09-22", "--to", "2026-09-22", "--boards", "KOSPI"), clock,
                    http, ms -> {}, PLENTY);
        } finally {
            System.setErr(old);
        }
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("캘린더상 거래일에 빈 목록").doesNotContain(KRX_KEY);
        assertThat(config.resolve("collector-cache/krx/KOSPI/2026/20260922.json")).doesNotExist();
        assertNoKeyAnywhere();
    }

    @Test
    void 오늘_자료가_아직_없으면_게시_전으로_적고_오류로_세지_않는다() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        LocalDate today = LocalDate.of(2026, 10, 8);
        FakeHttp http = new FakeHttp((u, n) -> {
            LocalDate d = LocalDate.parse(FakeHttp.query(u, "basDd"), KrxOpenApiClient.BAS_DD);
            return FakeHttp.ok(KrxFixtures.body(d, d.equals(today) ? List.of() : List.of("111111;가;;100;100;100;100;0;1;10")));
        });
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-06", "--to", "2026-10-08", "--boards", "KOSPI"), clock,
                http, ms -> {}, PLENTY)).isZero();
        Path report;
        try (Stream<Path> st = Files.list(reports)) {
            report = st.filter(p -> p.getFileName().toString().startsWith("krx-daily-")).findFirst().orElseThrow();
        }
        assertThat(Files.readString(report)).contains("10-08[-](게시 전)").doesNotContain("(거래일!)");
    }

    @Test
    void 최근_날_한_시장만_빈_목록이면_콘솔에_게시_전_시장_날짜를_한_줄로_알린다() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC); // 서울 10-08 19:00
        LocalDate wed = LocalDate.of(2026, 10, 7);
        FakeHttp http = new FakeHttp((u, n) -> {
            LocalDate d = LocalDate.parse(FakeHttp.query(u, "basDd"), KrxOpenApiClient.BAS_DD);
            boolean kospi = u.getPath().endsWith(KrxBoard.KOSPI.service);
            // 시장마다 다른 종목(같은 날 같은 종목이 두 시장에 나오면 스냅샷이 멈춘다)
            String row = kospi ? "111111;가;;100;100;100;100;0;1;10" : "222222;나;;200;200;200;200;0;1;10";
            return FakeHttp.ok(KrxFixtures.body(d, kospi && !d.isBefore(wed) ? List.of() : List.of(row)));
        });
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.out;
        int code;
        System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            code = CollectorMain.run(args("krx-daily", "--from", "2026-10-06", "--to", "2026-10-08", "--boards", "KOSPI,KOSDAQ"),
                    clock, http, ms -> {}, PLENTY);
        } finally {
            System.setOut(old);
        }
        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("게시 전으로 보고 넘긴 시장·날짜: 2026-10-07 KOSPI, 2026-10-08 KOSPI.")
                .contains("그날 일봉에서 빠지는 시장: 2026-10-07 KOSPI, 2026-10-08 KOSPI");
    }

    @Test
    void 키가_없거나_사용법이_틀리면_멈춘다() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        assertThat(CollectorMain.run(args("krx-daily"), clock, http, ms -> {}, PLENTY)).isEqualTo(1);
        assertThat(CollectorMain.run(new String[] {"recordings"}, clock, http, ms -> {}, PLENTY)).isEqualTo(2);
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-09", "--to", "2026-10-01"), clock, http, ms -> {}, PLENTY))
                .isEqualTo(2);
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 호출_상한에_닿으면_종료_코드_3() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08", "--max-calls", "2"),
                clock, krxServer(), ms -> {}, PLENTY)).isEqualTo(3);
    }

    @Test
    void 국내_장_시간에는_시작하지_않고_종료_코드_4() throws IOException {
        writeKeys();
        // 서울 2026-10-08(목) 08:30: 개장 60분 전부터 보호 구간
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T23:30:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-07"), clock, http, ms -> {}, PLENTY))
                .isEqualTo(4);
        assertThat(http.calls).isEmpty();
        // 실전 엔진을 끈 날에만 명시 옵션으로 푼다
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-07", "--allow-during-session",
                "--no-snapshot"), clock, http, ms -> {}, PLENTY)).isZero();
        assertThat(http.calls).isNotEmpty();
    }

    @Test
    void 미국_장_시간에는_시작하지_않는다() throws IOException {
        writeKeys();
        // 뉴욕 2026-10-08(목) 10:00 EDT = 14:00Z = 서울 23:00
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T14:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08"), clock, http, ms -> {}, PLENTY))
                .isEqualTo(4);
        assertThat(http.calls).isEmpty();
    }

    @Test
    void 실행_중_보호_구간에_들어서면_다음_호출_전에_멈춘다() throws IOException {
        writeKeys();
        // 첫 호출은 서울 07:59(구간 밖), 그 뒤 시각이 08:00(개장 60분 전)으로 넘어간다
        Instant before = Instant.parse("2026-10-07T22:59:00Z");
        Instant inside = Instant.parse("2026-10-07T23:00:00Z");
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return Clock.fixed(instant(), zone);
            }

            @Override
            public Instant instant() {
                return reads.get() == 0 ? before : inside;
            }
        };
        FakeHttp http = new FakeHttp((u, n) -> {
            reads.incrementAndGet();
            return FakeHttp.ok("{\"OutBlock_1\":[]}");
        });
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-06"), clock, http, ms -> {}, PLENTY))
                .isEqualTo(4);
        assertThat(http.calls).hasSize(1);
        assertThat(config.resolve("snapshots")).doesNotExist();
    }

    @Test
    void 감시의_정지_요청이_있으면_장_시간_해제_옵션을_줘도_시작하지_않고_파일을_지우지_않는다() throws IOException {
        writeKeys();
        Path stop = Files.createDirectories(config.resolve("collector-state")).resolve("stop-request");
        Files.writeString(stop, "2026-10-08T10:00:00Z");
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        for (String cmd : List.of("krx-daily", "dart-financials")) {
            assertThat(CollectorMain.run(args(cmd, "--allow-during-session"), clock, http, ms -> {}, PLENTY)).isEqualTo(4);
        }
        assertThat(http.calls).isEmpty();
        assertThat(stop).exists();
        assertThat(config.resolve("snapshots")).doesNotExist();
        // 감시가 요청을 거두면 다시 돈다
        Files.delete(stop);
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08", "--no-snapshot"), clock, http,
                ms -> {}, PLENTY)).isZero();
    }

    @Test
    void 실행_중_정지_요청이_생기면_다음_호출_전에_멈추고_파일을_지우지_않는다() throws IOException {
        writeKeys();
        Path stop = Files.createDirectories(config.resolve("collector-state")).resolve("stop-request");
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = new FakeHttp((u, n) -> {
            try {
                Files.writeString(stop, "now");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return FakeHttp.ok("{\"OutBlock_1\":[]}");
        });
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-06", "--allow-during-session"), clock,
                http, ms -> {}, PLENTY)).isEqualTo(4);
        assertThat(http.calls).hasSize(1);
        assertThat(stop).exists();
        assertThat(config.resolve("snapshots")).doesNotExist();
    }

    @Test
    void 디스크_여유가_기준_미만이면_시작하지_않는다() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        RunGuard.FreeSpace nineteenGb = dir -> 19L * 1024 * 1024 * 1024;
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08"), clock, http, ms -> {},
                nineteenGb)).isEqualTo(4);
        assertThat(http.calls).isEmpty();
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-08", "--min-free-gb", "10"),
                clock, http, ms -> {}, nineteenGb)).isZero();
    }

    @Test
    void 기본_배치에서_keep_snapshots가_옛_스냅샷을_정리한다() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        Path experiments = reports.resolve("experiments");
        Path quality = experiments.resolve("_data-quality");
        Files.createDirectories(experiments.resolve("EXP-001"));
        for (String to : List.of("2026-10-06", "2026-10-07", "2026-10-08")) {
            String[] a = {"krx-daily", "--from", "2026-10-05", "--to", to, "--boards", "KOSPI", "--config-dir", config.toString(),
                "--report-dir", quality.toString(), "--experiments-dir", experiments.toString(), "--keep-snapshots", "1"};
            assertThat(CollectorMain.run(a, clock, krxServer(), ms -> {}, PLENTY)).isZero();
            if (to.equals("2026-10-06")) {
                // 첫 스냅샷은 실험이 참조한다
                try (Stream<Path> s = Files.list(config.resolve("snapshots").resolve("krx-daily"))) {
                    String id = s.findFirst().orElseThrow().getFileName().toString();
                    Files.writeString(experiments.resolve("EXP-001").resolve("config.yaml"), "snapshot: " + id + "\n");
                }
            }
        }
        List<String> left;
        try (Stream<Path> s = Files.list(config.resolve("snapshots").resolve("krx-daily"))) {
            left = s.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).sorted().toList();
        }
        // 10-07 스냅샷만 지워진다: 10-06은 실험 참조, 10-08은 최근 1개. 품질 리포트는 셋 다 남는다
        assertThat(left).hasSize(2);
        assertThat(left.get(0)).startsWith("krx-daily-2026-10-06-");
        assertThat(left.get(1)).startsWith("krx-daily-2026-10-08-");
        try (Stream<Path> s = Files.list(quality)) {
            assertThat(s.count()).isEqualTo(3);
        }
    }

    @Test
    void 스냅샷을_쓰는_중_보호_구간에_들어서면_멈추고_임시_폴더를_남기지_않는다() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        // 캐시를 먼저 채운다
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-07", "--no-snapshot"), clock,
                krxServer(), ms -> {}, PLENTY)).isZero();
        // 서울 2026-10-08 07:59에서 시작해, 이틀 치를 읽은 뒤 08:00(국내 개장 60분 전)으로 넘어간다
        java.util.concurrent.atomic.AtomicReference<Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-07T22:59:00Z"));
        Clock moving = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return Clock.fixed(now.get(), zone);
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        RunGuard guard = new RunGuard(moving, List.of(com.autotrade.marketdata.calendar.ExchangeCalendar.bundled(Market.KRX)),
                false, PLENTY, 20, RunGuardTest.NO_STOP);
        int[] reads = {0};
        KrxDaySource src = CollectorMain.guarded(new KrxDaySource() {
            @Override
            public List<LocalDate> dates() {
                return List.of(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7));
            }

            @Override
            public List<KrxDailyRow> rows(LocalDate date) {
                if (++reads[0] == 2) {
                    now.set(Instant.parse("2026-10-07T23:00:00Z"));
                }
                return List.of();
            }
        }, guard);
        Path root = config.resolve("snapshots").resolve("krx-daily");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            try (SnapshotWriter w = new SnapshotWriter(root, "krx-daily")) {
                SnapshotWriter.Sink bars = w.open(SnapshotManifest.DAILY_BARS, SnapshotDailyBarSource.BAR_HEADER);
                SnapshotWriter.Sink hist = w.open(SnapshotManifest.INSTRUMENT_HISTORY, SnapshotDailyBarSource.HISTORY_HEADER);
                SnapshotWriter.Sink sh = w.open(SnapshotManifest.LISTED_SHARES, SnapshotDailyBarSource.SHARES_HEADER);
                KrxDailySnapshotBuilder.build(src, bars::write, hist::write, sh::write);
            }
        }).isInstanceOf(RunGuard.Refused.class);
        try (Stream<Path> s = Files.list(root)) {
            assertThat(s.toList()).isEmpty();
        }
    }

    @Test
    void 재무_스냅샷은_keep_snapshots를_줘도_지우지_않는다() throws IOException {
        writeKeys();
        Path experiments = Files.createDirectories(reports.resolve("experiments"));
        Path root = Files.createDirectories(config.resolve("snapshots").resolve("dart-financials"));
        Path old = Files.createDirectories(root.resolve("dart-financials-2026-03-01-aaaaaaaa"));
        Files.writeString(old.resolve("manifest.json"), "{\"snapshotId\":\"dart-financials-2026-03-01-aaaaaaaa\",\"schemaVersion\":1,"
                + "\"createdAt\":\"2026-03-02T10:00:00Z\",\"scriptVersion\":\"0.1.0\",\"sources\":[],\"files\":[]}");
        Clock clock = Clock.fixed(Instant.parse("2026-03-20T10:00:00Z"), ZoneOffset.UTC);
        String[] a = {"dart-financials", "--from", "2026-03-01", "--dart-base-url", "https://dart.test/api", "--config-dir",
            config.toString(), "--report-dir", experiments.resolve("_data-quality").toString(), "--experiments-dir",
            experiments.toString(), "--keep-snapshots", "1"};
        assertThat(CollectorMain.run(a, clock, new FakeHttp(DartFinancialCollectorTest.server(false)), ms -> {}, PLENTY)).isZero();
        assertThat(old).exists();
        try (Stream<Path> s = Files.list(root)) {
            assertThat(s.filter(p -> !p.getFileName().toString().startsWith(".")).count()).isEqualTo(2);
        }
    }

    @Test
    void 호출_상한은_약관_한도를_넘길_수_없고_기록은_캐시_폴더와_무관하게_합산된다() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        assertThat(CollectorMain.run(args("krx-daily", "--max-calls", "10001"), clock, http, ms -> {}, PLENTY)).isEqualTo(2);
        assertThat(CollectorMain.run(args("dart-financials", "--max-calls", "20001"), clock, http, ms -> {}, PLENTY)).isEqualTo(2);
        assertThat(http.calls).isEmpty();
        // 캐시 폴더를 바꿔 두 번 돌려도 같은 하루 상한(3회)을 나눠 쓴다
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-05", "--boards", "KOSPI,KOSDAQ",
                "--max-calls", "3", "--cache-dir", reports.resolve("c1").toString(), "--no-snapshot"), clock, krxServer(), ms -> {},
                PLENTY)).isZero();
        assertThat(CollectorMain.run(args("krx-daily", "--from", "2026-10-05", "--to", "2026-10-05", "--boards", "KOSPI,KOSDAQ",
                "--max-calls", "3", "--cache-dir", reports.resolve("c2").toString(), "--no-snapshot"), clock, krxServer(), ms -> {},
                PLENTY)).isEqualTo(3);
    }

    @Test
    void 주소는_https만_받는다() throws IOException {
        writeKeys();
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        FakeHttp http = krxServer();
        assertThat(CollectorMain.run(args("krx-daily", "--krx-base-url", "http://example.test"), clock, http, ms -> {}, PLENTY))
                .isEqualTo(2);
        assertThat(CollectorMain.run(args("dart-financials", "--dart-base-url", "ftp://x"), clock, http, ms -> {}, PLENTY))
                .isEqualTo(2);
        assertThat(http.calls).isEmpty();
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
