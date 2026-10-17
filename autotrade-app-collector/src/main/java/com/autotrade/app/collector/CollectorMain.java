package com.autotrade.app.collector;

import com.autotrade.config.ConfigPaths;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.DailyBarQualityCheck;
import com.autotrade.marketdata.daily.QualityReport;
import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.financial.FinancialFiling;
import com.autotrade.marketdata.financial.SnapshotFinancials;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 과거 데이터 수집 일괄 명령(ADR-008, D-051). 실행이 끝나면 종료한다.
 *
 * <pre>
 * autotrade-collector krx-daily       [--from 2010-01-04] [--to yyyy-MM-dd] [--boards KOSPI,KOSDAQ,KONEX] [--max-calls 9000]
 * autotrade-collector dart-financials [--from 2016-01-01] [--to yyyy-MM-dd] [--max-calls 10000]
 * 공통: [--config-dir 경로] [--cache-dir 경로] [--snapshot-dir 경로] [--report-dir experiments/_data-quality]
 *       [--interval-ms 200] [--no-snapshot] [--min-free-gb 20] [--keep-snapshots K] [--experiments-dir experiments] [--allow-during-session]
 * </pre>
 *
 * 종료 코드: 0 완료, 1 오류, 2 사용법 오류, 3 호출 상한·한도 초과로 멈춤(받은 것은 캐시에 남고 다음 날 이어 받음),
 * 4 실전 PC 자원 보호로 멈춤(감시의 정지 요청, 장 시간 보호 구간, 디스크 여유 부족. {@link RunGuard}).
 * {@code runtime-config.json}·{@code secrets.json}을 읽지 않는다. 키는 {@code collector-secrets.json}에서만 읽는다.
 */
public final class CollectorMain {

    static final String VERSION = "0.1.0";
    /** KRX Open API 이용약관 제8조 ④ 키당 하루 10,000회(D-050). */
    static final int KRX_TERMS_DAILY_LIMIT = 10_000;
    /** OpenDART 개발가이드 "일반적으로 20,000건" `[가정]`(D-050). */
    static final int DART_DAILY_LIMIT = 20_000;
    /** KRX 당일 자료를 받기 시작하는 서울 시각 `[가정]`: 그 전에는 기본 --to가 어제. */
    static final LocalTime KRX_SAME_DAY_FROM = LocalTime.of(18, 0);

    static final String KRX_SOURCE = "KRX Open API 일별매매정보(유가증권·코스닥·코넥스)";
    static final String KRX_LICENSE =
            "KRX Open API 이용약관(2025-12-26 시행): 비상업 목적, 제3자 제공 금지, 출처 '한국거래소 통계정보' (D-050)";
    static final String DART_SOURCE = "OpenDART 공시검색·단일회사 주요계정(사업보고서)";
    static final String DART_LICENSE = "OpenDART 이용약관(2020-01-21 시행), 개인 실험용 로컬 저장 (D-050)";

    private CollectorMain() {}

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC(), new JdkHttpGetter(), Thread::sleep, RunGuard.FILE_STORE));
    }

    static int run(String[] args, Clock clock, HttpGetter http, ApiCaller.Sleeper sleeper, RunGuard.FreeSpace freeSpace) {
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return 2;
        }
        RunGuard guard = new RunGuard(clock, List.of(ExchangeCalendar.bundled(Market.KRX), ExchangeCalendar.bundled(Market.US)),
                o.allowDuringSession, freeSpace, o.minFreeGb, o.stopRequest());
        // 감시와 다른 설정 폴더로 돌리면 정지 요청을 보지 못하므로 어디를 보는지 남긴다(PR #46 risk-ops N1)
        System.out.println("감시 정지 요청 확인 위치: " + o.stopRequest());
        try {
            guard.checkSession();
            guard.checkDisk(List.of(o.cacheDir, o.snapshotDir));
        } catch (RunGuard.Refused | IOException e) {
            System.err.println(e.getMessage());
            return e instanceof RunGuard.Refused ? 4 : 1;
        }
        CollectorSecrets secrets;
        try {
            secrets = CollectorSecrets.load(o.configDir);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        Redaction redaction = new Redaction(List.of(secrets.krxAuthKey(), secrets.openDartKey()));
        try {
            return switch (o.command) {
                case "krx-daily" -> krxDaily(o, secrets, clock, http, sleeper, guard);
                case "dart-financials" -> dartFinancials(o, secrets, clock, http, sleeper, guard);
                default -> throw new IllegalStateException("모르는 명령");
            };
        } catch (RunGuard.Refused e) {
            System.err.println(e.getMessage());
            return 4;
        } catch (CallBudget.BudgetExhausted e) {
            System.err.println(e.getMessage() + ". 받은 자료는 캐시에 남았고 다음 실행이 이어 받습니다.");
            return 3;
        } catch (IOException | RuntimeException e) {
            System.err.println("수집 실패: " + redaction.clean(e.getMessage()));
            return 1;
        }
    }

    static int krxDaily(Options o, CollectorSecrets secrets, Clock clock, HttpGetter http, ApiCaller.Sleeper sleeper,
            RunGuard guard) throws IOException {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(CallBudget.SEOUL));
        LocalDate to = o.to != null ? o.to
                : now.toLocalTime().isBefore(KRX_SAME_DAY_FROM) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        LocalDate from = o.from != null ? o.from : LocalDate.of(2010, 1, 4);
        CallBudget budget = new CallBudget(o.budgetDir(), "krx", o.maxCalls(9000), clock);
        Redaction redaction = new Redaction(List.of(secrets.krxAuthKey()));
        KrxOpenApiClient client = new KrxOpenApiClient(
                new ApiCaller(http, budget, redaction, sleeper, o.intervalMs, guard::checkSession), o.krxBase,
                secrets.krxAuthKey().reveal());
        KrxDailyCollector collector = new KrxDailyCollector(client, o.cacheDir, clock, ExchangeCalendar.bundled(Market.KRX));
        KrxDailyCollector.Result r = collector.collect(from, to, o.boards);
        System.out.println("KRX 일별매매정보: 새로 받은 응답 " + r.fetched() + "건, 오늘 누적 호출 " + budget.usedToday());
        notPublishedLine(r.notPublished(), o.boards, !o.noSnapshot).ifPresent(System.out::println);
        if (o.noSnapshot) {
            return 0;
        }
        guard.checkSession(); // 스냅샷·품질 리포트는 전 기간을 메모리에 올린다
        guard.checkDisk(List.of(o.snapshotDir));
        KrxDaySource source = guarded(collector.source(from, to, o.boards, r.uncached()), guard);
        Path root = o.snapshotDir.resolve("krx-daily");
        Path dir;
        KrxDailySnapshotBuilder.Stats stats;
        try (SnapshotWriter w = new SnapshotWriter(root, "krx-daily")) {
            SnapshotWriter.Sink bars = w.open(SnapshotManifest.DAILY_BARS, SnapshotDailyBarSource.BAR_HEADER);
            SnapshotWriter.Sink hist = w.open(SnapshotManifest.INSTRUMENT_HISTORY, SnapshotDailyBarSource.HISTORY_HEADER);
            SnapshotWriter.Sink shares = w.open(SnapshotManifest.LISTED_SHARES, SnapshotDailyBarSource.SHARES_HEADER);
            stats = KrxDailySnapshotBuilder.build(source, bars::write, hist::write, shares::write);
            if (stats.lastTradingDate() == null) {
                System.out.println("기간 안에 거래일이 없어 스냅샷을 만들지 않습니다");
                return 0;
            }
            String created = clock.instant().toString();
            String last = stats.lastTradingDate().toString();
            dir = w.commit("krx-daily", last, SnapshotManifest.DAILY_BARS, created, VERSION,
                    List.of(new SnapshotManifest.Source(KRX_SOURCE, "https://openapi.krx.co.kr", KRX_LICENSE, created)),
                    new SnapshotManifest.Coverage(stats.firstDate().toString(), last, last, created));
        }
        finishKrxSnapshot(o, root, dir, guard, stats, r.fetched(), budget.usedToday(), uncachedDates(r));
        return 0;
    }

    /**
     * 커밋한 일봉 스냅샷의 뒷단계: 옛 스냅샷 정리를 먼저 하고(리포트 단계가 실패해도 정리는 끝나 있게, QA PR #39 F1),
     * 품질 리포트는 일봉을 메모리에 다 올리지 않는 날짜 순 검사로 만든다(전 기간 약 1,100만 행도 2GB 힙 안에서).
     */
    static Path finishKrxSnapshot(Options o, Path root, Path dir, RunGuard guard, KrxDailySnapshotBuilder.Stats stats,
            int fetched, int usedToday, Set<LocalDate> pending) throws IOException {
        System.out.println("스냅샷: " + dir);
        prune(o, root, "krx-daily", dir);
        QualityReport report = DailyBarQualityCheck.checkSnapshot(dir, guard::checkSession);
        Path reportFile = report.writeTo(o.reportDir);
        ExchangeCalendar cal = ExchangeCalendar.bundled(Market.KRX);
        CollectorReports.appendDailySummary(reportFile, stats, fetched, usedToday, cal, pending);
        System.out.println("품질 리포트: " + reportFile + " (오류 " + report.errorCount() + "건)");
        CollectorReports.DailyWarnings w = CollectorReports.warnings(stats, cal, pending);
        System.out.println("빈 평일 " + stats.emptyWeekdays().size() + "일(캘린더상 거래일 " + w.emptyTradingDays() + "), 시장 하나만 빈 날 "
                + w.boardGaps() + "건, 같은 날 여러 종목 수정 이벤트 " + w.massAdjustmentDays() + "일"
                + (w.any() ? ". 품질 리포트의 해당 칸을 확인해 주세요" : ""));
        return reportFile;
    }

    static int dartFinancials(Options o, CollectorSecrets secrets, Clock clock, HttpGetter http, ApiCaller.Sleeper sleeper,
            RunGuard guard) throws IOException {
        LocalDate yesterday = LocalDate.now(clock.withZone(CallBudget.SEOUL)).minusDays(1);
        LocalDate to = o.to != null ? o.to : yesterday;
        LocalDate from = o.from != null ? o.from : LocalDate.of(2016, 1, 1);
        CallBudget budget = new CallBudget(o.budgetDir(), "opendart", o.maxCalls(10000), clock);
        Redaction redaction = new Redaction(List.of(secrets.openDartKey()));
        OpenDartClient client = new OpenDartClient(new ApiCaller(http, budget, redaction, sleeper, o.intervalMs, guard::checkSession), budget,
                o.dartBase, secrets.openDartKey().reveal());
        DartFinancialCollector collector = new DartFinancialCollector(client, o.cacheDir, clock);
        DartFinancialCollector.Result r = collector.collect(from, to);
        System.out.println("OpenDART: 새 공시 " + r.newFilings() + "건, 계정 호출 " + r.fetchedAccounts() + "건, 대기 "
                + r.pending() + "건, 훑은 날 " + r.scannedThrough() + ", 오늘 누적 호출 " + budget.usedToday());
        if (o.noSnapshot) {
            return 0;
        }
        guard.checkSession();
        guard.checkDisk(List.of(o.snapshotDir));
        List<DartSnapshotBuilder.Input> inputs = new ArrayList<>();
        for (var f : collector.filings()) {
            inputs.add(new DartSnapshotBuilder.Input(f, collector.accountsOf(f.path("rcept_no").asText())));
        }
        DartSnapshotBuilder.Result built = DartSnapshotBuilder.build(inputs);
        if (built.filings().isEmpty()) {
            System.out.println("받은 공시가 없어 스냅샷을 만들지 않습니다");
            return 0;
        }
        Path dir;
        Path root = o.snapshotDir.resolve("dart-financials");
        try (SnapshotWriter w = new SnapshotWriter(root, "dart-financials")) {
            SnapshotWriter.Sink sink = w.open(SnapshotManifest.FINANCIAL_FILINGS, SnapshotFinancials.HEADER);
            for (FinancialFiling f : built.filings()) {
                sink.write(DartSnapshotBuilder.row(f));
            }
            String created = clock.instant().toString();
            dir = w.commit("dart-financials", String.valueOf(r.scannedThrough()), SnapshotManifest.FINANCIAL_FILINGS, created,
                    VERSION, List.of(new SnapshotManifest.Source(DART_SOURCE, "https://opendart.fss.or.kr", DART_LICENSE, created)),
                    new SnapshotManifest.Coverage(String.valueOf(built.stats().firstReceipt()), null, null, created));
        }
        SnapshotFinancials check = SnapshotFinancials.load(dir); // 읽는 쪽 검사(접수일·해시)를 그대로 통과하는지
        Path reportFile = CollectorReports.writeFinancial(o.reportDir, check.manifest(), built.stats(), r, budget.usedToday());
        System.out.println("스냅샷: " + dir + "\n품질 리포트: " + reportFile);
        if (o.keepSnapshots != null) {
            // 재무 스냅샷은 그때까지의 공시·대기 확정 상태로 만들어져, 지우면 같은 내용(같은 ID)으로 다시 만들 수 없다(QA PR #39 F2)
            System.out.println("재무 스냅샷은 지우면 재현할 수 없어 --keep-snapshots로 정리하지 않습니다");
        }
        return 0;
    }

    /**
     * 게시 전으로 보고 넘긴 시장·날짜 한 줄(PR #141 strategy-researcher R3). 일부 시장만 빈 날은 이번 스냅샷의 그날 일봉에서
     * 그 시장이 빠지므로 따로 적는다. {@code snapshot}이 거짓(--no-snapshot)이면 이번에 스냅샷을 만들지 않으므로 "지금 스냅샷을
     * 만들면"으로 적는다(PR #143 architect N2). 넘긴 날이 없으면 비어 있다.
     */
    static Optional<String> notPublishedLine(List<KrxDailyCollector.NotPublished> skipped, Set<KrxBoard> boards,
            boolean snapshot) {
        if (skipped.isEmpty()) {
            return Optional.empty();
        }
        Map<LocalDate, List<String>> byDate = new TreeMap<>();
        for (KrxDailyCollector.NotPublished n : skipped) {
            byDate.computeIfAbsent(n.date(), k -> new ArrayList<>()).add(n.board().name());
        }
        List<String> all = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        byDate.forEach((d, bs) -> {
            String item = d + " " + String.join("·", bs);
            all.add(item);
            if (bs.size() < boards.size()) {
                partial.add(item);
            }
        });
        return Optional.of("게시 전으로 보고 넘긴 시장·날짜: " + String.join(", ", all)
                + ". 캐시에 두지 않았고 다음 실행이 다시 받습니다"
                + (partial.isEmpty() ? ""
                        : ". 같은 날 다른 시장은 자료가 있어 " + (snapshot ? "이번 스냅샷의" : "지금 스냅샷을 만들면")
                                + " 그날 일봉에서 빠지는 시장: " + String.join(", ", partial)));
    }

    /** 이번 실행에서 캐시하지 않은 응답의 날짜(오늘, 최근 게시 전 빈 날). */
    static Set<LocalDate> uncachedDates(KrxDailyCollector.Result r) {
        Set<LocalDate> out = new java.util.TreeSet<>();
        for (String k : r.uncached().keySet()) {
            out.add(LocalDate.parse(k.substring(k.indexOf('|') + 1)));
        }
        return out;
    }

    /** 스냅샷을 쓰는 동안에도 하루 읽을 때마다 장 시간 보호 구간을 본다(들어서면 쓰던 임시 폴더를 지우고 멈춤). */
    static KrxDaySource guarded(KrxDaySource source, RunGuard guard) {
        return new KrxDaySource() {
            @Override
            public List<LocalDate> dates() {
                return source.dates();
            }

            @Override
            public List<KrxDailyRow> rows(LocalDate date) throws IOException {
                guard.checkSession();
                return source.rows(date);
            }
        };
    }

    /** {@code --keep-snapshots}를 줬을 때만 옛 일봉 스냅샷을 정리한다(재무 스냅샷은 대상 아님). 실험 기록이 참조하는 ID는 남긴다({@link SnapshotRetention}). */
    private static void prune(Options o, Path root, String prefix, Path current) throws IOException {
        if (o.keepSnapshots == null) {
            return;
        }
        if (!java.nio.file.Files.isDirectory(o.experimentsDir)) {
            System.out.println("실험 폴더 " + o.experimentsDir.toAbsolutePath() + "가 없어 참조를 확인할 수 없으므로 스냅샷을 정리하지 않습니다"
                    + " (--experiments-dir로 지정)");
            return;
        }
        List<Path> removed = SnapshotRetention.prune(root, prefix, o.keepSnapshots, current, o.experimentsDir, o.reportDir);
        System.out.println("옛 스냅샷 " + removed.size() + "개 정리(최근 " + o.keepSnapshots + "개와 실험 기록이 참조하는 것은 남김)");
    }

    static final String USAGE = """
            사용법: autotrade-collector <krx-daily|dart-financials> [옵션]
              --from yyyy-MM-dd   --to yyyy-MM-dd   --boards KOSPI,KOSDAQ,KONEX   --max-calls N   --interval-ms N
              --config-dir 경로   --cache-dir 경로   --snapshot-dir 경로   --report-dir 경로   --no-snapshot
              --min-free-gb N(기본 20)   --keep-snapshots K(주면 최근 K개만 남김)   --experiments-dir experiments   --allow-during-session(실전 엔진을 끈 날만)
              --krx-base-url https://...  --dart-base-url https://...""";

    /** 명령줄 옵션. 설정 파일을 쓰지 않는다(ADR-008 2.2). */
    static final class Options {
        String command;
        LocalDate from;
        LocalDate to;
        Set<KrxBoard> boards = EnumSet.allOf(KrxBoard.class);
        Integer maxCalls;
        long intervalMs = 200;
        Path configDir;
        Path cacheDir;
        Path snapshotDir;
        Path reportDir = Path.of("experiments", "_data-quality");
        Path experimentsDir = Path.of("experiments");
        boolean noSnapshot;
        boolean allowDuringSession;
        long minFreeGb = RunGuard.DEFAULT_MIN_FREE_GB;
        Integer keepSnapshots;
        String krxBase = KrxOpenApiClient.DEFAULT_BASE;
        String dartBase = OpenDartClient.BASE;

        int maxCalls(int defaultCap) {
            return maxCalls == null ? defaultCap : maxCalls;
        }

        /**
         * 호출 기록 위치. 캐시 폴더가 아니라 설정 폴더 기준이라 {@code --cache-dir}를 바꿔 돌려도 같은 키의 하루 호출이 합산된다
         * (QA PR #39 R3).
         */
        Path budgetDir() {
            return configDir.resolve("collector-state");
        }

        /** 감시 프로세스가 만드는 정지 요청 파일(broker-and-fault-design 5.2 0번, D-058). */
        Path stopRequest() {
            return budgetDir().resolve("stop-request");
        }

        static Options parse(String[] args) {
            if (args.length == 0 || !Set.of("krx-daily", "dart-financials").contains(args[0])) {
                throw new IllegalArgumentException("명령을 krx-daily 또는 dart-financials로 주세요");
            }
            Options o = new Options();
            o.command = args[0];
            Map<String, String> kv = new HashMap<>();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--no-snapshot")) {
                    o.noSnapshot = true;
                } else if (a.equals("--allow-during-session")) {
                    o.allowDuringSession = true;
                } else if (a.startsWith("--") && i + 1 < args.length) {
                    kv.put(a, args[++i]);
                } else {
                    throw new IllegalArgumentException("모르는 인자: " + a);
                }
            }
            try {
                for (Map.Entry<String, String> e : kv.entrySet()) {
                    String v = e.getValue();
                    switch (e.getKey()) {
                        case "--from" -> o.from = LocalDate.parse(v);
                        case "--to" -> o.to = LocalDate.parse(v);
                        case "--boards" -> {
                            Set<KrxBoard> b = EnumSet.noneOf(KrxBoard.class);
                            for (String s : v.split(",")) {
                                b.add(KrxBoard.valueOf(s.strip()));
                            }
                            o.boards = b;
                        }
                        case "--max-calls" -> o.maxCalls = Integer.parseInt(v);
                        case "--interval-ms" -> o.intervalMs = Long.parseLong(v);
                        case "--config-dir" -> o.configDir = Path.of(v);
                        case "--cache-dir" -> o.cacheDir = Path.of(v);
                        case "--snapshot-dir" -> o.snapshotDir = Path.of(v);
                        case "--report-dir" -> o.reportDir = Path.of(v);
                        case "--experiments-dir" -> o.experimentsDir = Path.of(v);
                        case "--krx-base-url" -> o.krxBase = https(v);
                        case "--dart-base-url" -> o.dartBase = https(v);
                        case "--min-free-gb" -> o.minFreeGb = positive(Long.parseLong(v));
                        case "--keep-snapshots" -> o.keepSnapshots = (int) positive(Integer.parseInt(v));
                        default -> throw new IllegalArgumentException("모르는 옵션: " + e.getKey());
                    }
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("옵션 값이 틀렸습니다: " + e.getMessage(), e);
            }
            int limit = o.command.equals("krx-daily") ? KRX_TERMS_DAILY_LIMIT : DART_DAILY_LIMIT;
            if (o.maxCalls != null && (o.maxCalls < 1 || o.maxCalls > limit)) {
                // 약관 한도를 넘기면 키가 정지될 수 있다(QA PR #39 R3)
                throw new IllegalArgumentException("--max-calls는 1 이상 하루 한도 " + limit + " 이하여야 합니다");
            }
            if (o.from != null && o.to != null && o.to.isBefore(o.from)) {
                throw new IllegalArgumentException("--to가 --from보다 앞입니다");
            }
            if (o.configDir == null) {
                // 4개 프로세스와 같은 설정 폴더 규칙(시스템 속성 → AUTOTRADE_CONFIG_DIR → ~/.autotrade). 파일은 열지 않는다
                o.configDir = ConfigPaths.defaultLocation().runtimeFile().getParent();
            }
            o.cacheDir = o.cacheDir != null ? o.cacheDir : o.configDir.resolve("collector-cache");
            o.snapshotDir = o.snapshotDir != null ? o.snapshotDir : o.configDir.resolve("snapshots");
            return o;
        }

        /** 키가 평문으로 나가거나 엉뚱한 곳으로 가지 않게 https만 받는다(risk-ops 검토 PR #39 N1). */
        private static String https(String url) {
            if (!url.startsWith("https://")) {
                throw new IllegalArgumentException("주소는 https://로 시작해야 합니다");
            }
            return url;
        }

        private static long positive(long v) {
            if (v < 1) {
                throw new IllegalArgumentException("1 이상이어야 합니다");
            }
            return v;
        }
    }
}
