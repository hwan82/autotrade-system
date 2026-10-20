package com.autotrade.sim.recording;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.exchange.MidpointTrades;
import com.autotrade.sim.metrics.MetricsCalculator;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.combo.CombinationPlan;
import com.autotrade.sim.combo.SnapshotInputs;
import com.autotrade.sim.selection.SelectionCandidates;
import com.autotrade.sim.selection.SelectionPreparer;
import com.autotrade.sim.selection.SelectionResultFile;
import com.autotrade.sim.selection.SelectionSource;
import com.autotrade.sim.selection.SessionSelections;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import com.autotrade.strategy.profile.ProfileYaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 녹화 실험(D-069): 녹화 폴더의 실녹화일들을 {@link RecordedMarketDataSet}으로 읽어 장중 전략 프로필들을 실제
 * {@link KrxMarketRules}(캘린더·매매 단계) 위에서 돌린다. 계좌마다 실전과 같은 {@link RiskChain}(등록 파일의 제외 종목 스냅샷)을
 * 통과한다. 결과 파일은 실행 시각 같은 메타가 없어 같은 입력이면 바이트까지 같다(SIM-09).
 *
 * <ul>
 *   <li>{@code inputs.yaml}: 데이터셋 버전, 녹화일별 입력 품질(파일 SHA-256, 줄 수, 정상 종료·잘림, 구간 수, 표시 없는 재기동
 *       경계와 끼운 공백 수), 비용·체결 모델, 블랙리스트 지문, 결정론 확인, {@code detail.txt} SHA-256.</li>
 *   <li>{@code results.md}: 계좌별 표(관측 일수, 비용 차감 성과), 시세 공백 목록.</li>
 *   <li>{@code detail.txt}: {@link ReportWriter} 정규 보고서(체결 원장, {@code data-gap} 줄). 결정론 확인에 쓴다.</li>
 * </ul>
 *
 * <p>녹화 파일 자체는 결과 폴더에 쓰지 않는다(시세 재배포 금지, ADR-003 4장).
 */
public final class RecordingExperiment {

    private RecordingExperiment() {
    }

    /** 실험 결과와 결과 폴더에 쓸 파일(이름 → 내용). */
    public record Outcome(ExperimentSpec spec, SimulationReport report, RecordedMarketDataSet data,
            Map<String, String> files) {

        public Outcome {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(data, "data");
            files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
    }

    /**
     * @param repoRoot 저장소 루트({@code strategies/}가 있는 곳)
     * @param recordingsRoot 녹화 폴더({@code <설정 폴더>/recordings}, ADR-003 2.3)
     * @param verifyDeterminism true면 병렬 수 1로 한 번 더 돌려 상세 보고서가 바이트까지 같은지 본다
     * @param gate 정지 확인(D-063). 녹화를 읽기 전, 결정론 확인 실행 전, 그리고 실행기가 Run 시작 전·거래일마다 묻는다
     * @throws IllegalArgumentException 등록 파일과 입력이 맞지 않을 때(아무 Run도 시작하지 않는다)
     * @throws ExperimentStoppedException 문이 멈추라고 했을 때(결과 없음)
     */
    public static Outcome run(RecordingPlan plan, Path repoRoot, Path recordingsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        return run(plan, repoRoot, recordingsRoot, null, code, verifyDeterminism, log, gate);
    }

    /**
     * @param snapshotsRoot 수집 스냅샷 폴더({@code <설정 폴더>/snapshots}). 선정 조합 계좌가 있을 때만 쓴다(D-124)
     */
    public static Outcome run(RecordingPlan plan, Path repoRoot, Path recordingsRoot, Path snapshotsRoot,
            CodeVersion code, boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        ExchangeCalendar calendar = ExchangeCalendar.bundled(plan.market());
        gate.check(); // 힙을 크게 쓰는 녹화 읽기 전에 먼저 본다
        RecordingLayout layout = new RecordingLayout(recordingsRoot);
        log.accept("녹화 읽기: " + layout.root() + " " + plan.market() + " " + plan.from() + " ~ " + plan.to());
        RecordedMarketDataSet data = RecordedMarketDataSet.open(layout, plan.market(), plan.from(), plan.to());
        gate.check();
        for (RecordedMarketDataSet.DayInfo d : data.days()) {
            if (!calendar.isTradingDay(d.date())) {
                throw new IllegalArgumentException("캘린더상 휴장일의 녹화입니다: " + d.date());
            }
            log.accept("녹화일 " + d.date() + ": 줄 " + d.recordedEvents() + ", 정상 종료 " + d.complete() + ", 잘림 "
                    + d.truncated() + ", 구간 " + d.segments() + ", 끼운 공백 " + d.insertedGaps());
        }
        // 녹화에는 상품 유형이 없다. 주식 호가 단위로 보고, 그 단위에 맞지 않는 가격이 있으면 거절한다(D-075)
        KrxMarketRules rules = new KrxMarketRules(calendar, s -> InstrumentKind.STOCK);
        TreeSet<Symbol> symbols = new TreeSet<>();
        for (MarketEvent e : data.events()) {
            symbols.add(e.symbol());
            requireStockTicks(rules, e);
        }
        for (Symbol s : symbols) {
            if (!plan.boards().containsKey(s.code())) {
                throw new IllegalArgumentException("등록 파일 boards에 소속부가 없는 녹화 종목입니다: " + s.code());
            }
        }
        plan.benchmark().ifPresent(b -> {
            if (!symbols.contains(Symbol.krx(b))) {
                throw new IllegalArgumentException("벤치마크 종목이 녹화에 없습니다: " + b);
            }
        });
        log.accept("이벤트 " + data.events().size() + ", 종목 " + symbols.size() + ", 데이터셋 " + data.version());

        CostModel cost = CostModels.krDefault(s -> plan.boards().get(s.code()));
        CatalogStrategyFactory factory = new CatalogStrategyFactory(rules);
        List<Combination> combinations = new ArrayList<>();
        for (RecordingPlan.ProfileRef ref : plan.profiles()) {
            StrategyProfile p = loadStrategy(repoRoot, ref);
            factory.validate(p);
            combinations.add(Combination.of(p));
        }
        // 선정 조합 계좌는 profiles 계좌 뒤에 등록 파일 순서로(D-124). 선정 없는 계좌의 번호·시드는 그대로다
        List<Combination> selected = selectionCombinations(repoRoot, plan.selections(), factory);
        combinations.addAll(selected);
        List<BlacklistEntry> black = plan.blacklist().stream()
                .map(c -> new BlacklistEntry(Symbol.krx(c), BlacklistType.MANUAL, "실험 등록 파일 " + plan.id())).toList();
        BlacklistSnapshot blacklist = BlacklistSnapshot.of(black);
        RiskChain risk = RiskChain.builder(blacklist).build();
        Symbol benchmark = plan.benchmark().map(Symbol::krx).orElse(null);
        ExperimentSpec spec = new ExperimentSpec(plan.id(), plan.from(), plan.to(), plan.seed(), plan.seedCapital(),
                cost.id(), data.version(), ClockMode.REPLAY, combinations);
        ExecutionSettings execution = ExecutionSettings.defaults();

        // 선정 조합 계좌가 있으면 녹화일마다 고정 스냅샷으로 선정을 계산해 고정 파일·적용 기록을 만들고(실시간 모의와 같은
        // 준비→적용 경로), 실행기는 그 적용 기록을 읽는다(ADR-012 3.5·3.6, D-124)
        SelectionStage stage = selected.isEmpty() ? null
                : SelectionStage.prepare(plan, selectionProfiles(selected), data, calendar, snapshotsRoot, code, log, gate);
        SimulationReport report;
        String detail;
        String determinism = "확인 안 함(--verify-determinism으로 확인)";
        int parallel;
        try {
            SelectionSource source = stage == null ? null : stage.files.source();
            // 실제 병렬 수: 등록 파일 parallelism과 실행 명령 상한 중 작은 쪽(D-089). 결정론 기록에도 이 값을 쓴다
            parallel = Math.min(plan.parallelism(), gate.maxParallelRuns());
            log.accept("프로필 " + combinations.size() + "개 실행(병렬 " + parallel + ")");
            report = new SimulationExperimentRunner(data, rules, cost, factory, risk, execution, code,
                    parallel, benchmark, source, gate).runDetailed(spec);
            detail = ReportWriter.render(report);
            if (verifyDeterminism && parallel == 1) {
                determinism = "확인 안 함(실제 병렬 수가 1이라 병렬 대 직렬 비교가 안 됨. -PmaxParallelRuns와 등록 파일 parallelism을 2 이상으로)";
                log.accept("결정론 확인 생략: 실제 병렬 수가 1입니다");
            } else if (verifyDeterminism) {
                log.accept("결정론 확인: 병렬 1로 다시 실행");
                gate.check();
                String second = ReportWriter.render(new SimulationExperimentRunner(data, rules, cost, factory, risk,
                        execution, code, 1, benchmark, source, gate).runDetailed(spec));
                determinism = second.equals(detail)
                        ? "같음(병렬 " + parallel + " vs 1, 상세 보고서 바이트 일치)" : "다름";
                if (!second.equals(detail)) {
                    log.accept("결정론 확인 실패: 두 실행의 상세 보고서가 다릅니다");
                }
            }
        } finally {
            if (stage != null) {
                stage.close();
            }
        }

        List<SessionSelections.Applied> applied = stage == null ? List.of() : stage.applied;
        Map<String, String> files = new LinkedHashMap<>();
        files.put("inputs.yaml", inputs(plan, data, symbols, cost, execution, blacklist, report, determinism, detail,
                applied));
        files.put("results.md", results(plan, data, cost, execution, blacklist, report, determinism, applied));
        files.put("detail.txt", detail);
        if (stage != null) {
            files.putAll(stage.texts);
        }
        return new Outcome(spec, report, data, files);
    }

    /**
     * 녹화 실험의 선정 준비 폴더(임시). 녹화일마다 결과 파일과 적용 기록을 만들고, 실행이 끝나면 내용을 결과 폴더
     * {@code selections/}에 쓸 파일로 옮긴 뒤 지운다.
     */
    private static final class SelectionStage implements AutoCloseable {
        private final Path dir;
        private final SessionSelections files;
        private final List<SessionSelections.Applied> applied = new ArrayList<>();
        private final Map<String, String> texts = new TreeMap<>();

        private SelectionStage(Path dir, SessionSelections files) {
            this.dir = dir;
            this.files = files;
        }

        static SelectionStage prepare(RecordingPlan plan, List<SelectionProfile> profiles, RecordedMarketDataSet data,
                ExchangeCalendar calendar, Path snapshotsRoot, CodeVersion code, Consumer<String> log, RunGate gate) {
            if (snapshotsRoot == null) {
                throw new IllegalArgumentException("선정 조합 계좌가 있는데 수집 스냅샷 폴더가 정해지지 않았습니다");
            }
            RegisteredSelections sel = plan.selections();
            SnapshotInputs in = SnapshotInputs.load(plan.market(), sel.daily().orElseThrow(), sel.financials(),
                    snapshotsRoot, calendar, gate, log, (first, last) -> { });
            Path dir;
            try {
                dir = Files.createTempDirectory("autotrade-recording-selections-");
            } catch (IOException e) {
                throw new UncheckedIOException("선정 준비 임시 폴더를 만들 수 없습니다", e);
            }
            SelectionStage stage = new SelectionStage(dir, new SessionSelections(dir, profiles, plan.boards().keySet(),
                    java.util.Set.copyOf(plan.blacklist()), calendar::previousTradingDay));
            try {
                SelectionCalculator calc = new SelectionCalculator(in.selectionData());
                // 결과 파일이 실행마다 같게 만든 시각은 남기지 않는다(SIM-09)
                SelectionResultFile.Producer producer = new SelectionResultFile.Producer(code.commit(), code.dirty(), null);
                for (RecordedMarketDataSet.DayInfo d : data.days()) {
                    java.time.LocalDate cutoff = calendar.previousTradingDay(d.date());
                    for (SelectionPreparer.Outcome o : SelectionPreparer.prepare(stage.files, d.date(), calc, in.last(),
                            cutoff, producer, gate::check)) {
                        log.accept("선정 " + d.date() + " " + o.selection() + ": " + o.status()
                                + (o.reason() == null ? "" : " " + o.reason() + " " + o.detail()));
                    }
                    stage.applied.add(stage.files.apply(d.date()));
                }
                stage.collect();
            } catch (RuntimeException e) {
                stage.close();
                throw e;
            }
            return stage;
        }

        /** 임시 폴더의 결과 파일·적용 기록을 결과 폴더 상대 경로 {@code selections/<날짜>/<이름>}으로 모은다. */
        private void collect() {
            try (Stream<Path> walk = Files.walk(files.root())) {
                for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String name = f.getFileName().toString();
                    if (name.startsWith(".tmp-")) {
                        continue;
                    }
                    String rel = SessionSelections.DIR + "/" + files.root().relativize(f).toString().replace('\\', '/');
                    texts.put(rel, Files.readString(f, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("선정 준비 파일을 읽을 수 없습니다", e);
            }
        }

        @Override
        public void close() {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(f);
                }
            } catch (IOException ignored) {
                // 임시 폴더 정리 실패는 결과에 영향이 없다
            }
        }
    }

    /**
     * 등록 파일의 선정 조합을 계좌 조합으로 만든다(녹화 실험·실시간 모의, D-124). 선정은 단타 장전 선정({@code PRE_OPEN_DAILY})만
     * 받는다: 고정 파일이 하루 적용 범위를 전제로 하고, 장중 전략과 스타일이 맞아야 한다(ADR-007 2.1).
     */
    public static List<Combination> selectionCombinations(Path repoRoot, RegisteredSelections selections,
            CatalogStrategyFactory factory) {
        Map<String, SelectionProfile> loaded = new LinkedHashMap<>();
        List<Combination> out = new ArrayList<>();
        for (RegisteredSelections.Entry e : selections.entries()) {
            SelectionProfile s = loaded.computeIfAbsent(e.selection().text(), k -> CombinationExperiment.loadSelection(
                    repoRoot, new CombinationPlan.Ref(e.selection().id(), e.selection().version())));
            if (s.refresh() != SelectionRefresh.PRE_OPEN_DAILY) {
                throw new IllegalArgumentException("녹화 실험·실시간 모의의 선정은 단타 장전 선정(PRE_OPEN_DAILY)만 받습니다: "
                        + s.key() + " = " + s.refresh());
            }
            StrategyProfile p = loadStrategy(repoRoot, e.strategy());
            factory.validate(p);
            out.add(Combination.of(s, p));
        }
        return out;
    }

    /** 조합들의 선정 프로필(선정 해시마다 하나, 해시순). */
    public static List<SelectionProfile> selectionProfiles(List<Combination> combinations) {
        TreeMap<String, SelectionProfile> out = new TreeMap<>();
        for (Combination c : combinations) {
            c.selection().ifPresent(s -> out.putIfAbsent(s.selectionHash(), s));
        }
        return List.copyOf(out.values());
    }

    /**
     * 녹화 가격이 주식 호가 단위에 맞는지 본다(D-075, architect PR #74 R2). ETF·ETN(5원 단위)은 주식 단위에 맞지 않는 가격이
     * 나와 여기서 걸린다. 비용 표에 ETF·ETN 과세 구분이 없어(증권거래세 비과세 `[추론]`, docs/research/q-004-trading-costs.md)
     * 녹화 실험은 아직 주식만 받는다.
     *
     * @throws IllegalArgumentException 호가 단위에 맞지 않는 가격이 있을 때
     */
    static void requireStockTicks(MarketRules rules, MarketEvent e) {
        offStockTick(rules, e).ifPresent(price -> {
            throw new IllegalArgumentException("주식 호가 단위에 맞지 않는 녹화 가격입니다: " + e.symbol().code() + " "
                    + price.toPlainString() + " (sequence " + e.sequence() + "). ETF·ETN 같은 주식 밖 종목은 녹화 실험이 아직 "
                    + "받지 않으니 녹화 종목에서 빼고 다시 녹화하세요");
        });
    }

    /**
     * 이벤트의 체결가·호가 중 주식 호가 단위에 맞지 않는 첫 가격(없으면 빈 값). 녹화 실험과 실시간 모의(D-076)가 같은 검사를
     * 쓴다(D-075 4번). 체결가가 호가 단위의 정확한 반 칸이면 중간가 체결이라 단위 밖으로 보지 않는다(D-118,
     * {@link MidpointTrades}). 호가 가격에는 예외가 없다.
     */
    public static Optional<BigDecimal> offStockTick(MarketRules rules, MarketEvent e) {
        List<BigDecimal> prices = new ArrayList<>();
        if (e instanceof TradeTick t) {
            if (!MidpointTrades.isMidpoint(rules, t.symbol(), t.price())) {
                prices.add(t.price());
            }
        } else if (e instanceof OrderBookSnapshot b) {
            b.asks().forEach(l -> prices.add(l.price()));
            b.bids().forEach(l -> prices.add(l.price()));
        }
        for (BigDecimal price : prices) {
            if (rules.roundToTick(e.symbol(), price, RoundingMode.DOWN).compareTo(price) != 0) {
                return Optional.of(price);
            }
        }
        return Optional.empty();
    }

    /** 등록 파일을 읽어 실험을 돌리고 결과 파일을 등록 파일 옆에 쓴다. 멈추면 결과 파일을 하나도 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path recordingsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        return runAndWrite(experimentDir, repoRoot, recordingsRoot, null, code, verifyDeterminism, log, gate);
    }

    /**
     * @param snapshotsRoot 수집 스냅샷 폴더. 선정 조합 계좌가 있으면 필요하다(D-124). 선정 고정 파일은 결과 폴더
     *     {@code selections/}에 쓴다(저장소에는 커밋하지 않음: 종목 전체 평가가 들어 있어 크다. 내용 해시는 inputs.yaml에 남는다)
     */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path recordingsRoot, Path snapshotsRoot,
            CodeVersion code, boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        Path file = experimentDir.resolve("experiment.yaml");
        RecordingPlan plan;
        try {
            plan = RecordingPlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 읽을 수 없습니다: " + file, e);
        }
        if (!plan.id().value().equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + plan.id());
        }
        Outcome out = run(plan, repoRoot, recordingsRoot, snapshotsRoot, code, verifyDeterminism, log, gate);
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            Path target = experimentDir.resolve(f.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
            }
            log.accept("작성: " + target);
        }
        return out;
    }

    public static String inputs(RecordingPlan plan, RecordingInput data, TreeSet<Symbol> symbols, CostModel cost,
            ExecutionSettings execution, BlacklistSnapshot blacklist, SimulationReport report, String determinism,
            String detail) {
        return inputs(plan, data, symbols, cost, execution, blacklist, report, determinism, detail, List.of());
    }

    /**
     * @param applied 선정 조합 계좌의 날짜별 적용 기록(날짜순). 비어 있으면 선정 칸을 쓰지 않는다(선정 없는 실험은 예전과 같은 파일)
     */
    public static String inputs(RecordingPlan plan, RecordingInput data, TreeSet<Symbol> symbols, CostModel cost,
            ExecutionSettings execution, BlacklistSnapshot blacklist, SimulationReport report, String determinism,
            String detail, List<SessionSelections.Applied> applied) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 실험 입력(실행기가 씀, D-069). 녹화 파일은 저장소에 넣지 않는다(ADR-003 4장).\n");
        sb.append("experiment: ").append(plan.id()).append('\n');
        sb.append("code: \"").append(report.result().codeCommit()).append(report.result().codeDirty() ? " dirty" : "")
                .append("\"\n");
        sb.append("data-set: \"").append(data.version()).append("\"\n");
        sb.append("adapter: ").append(RecordedMarketDataSet.ADAPTER_VERSION).append('\n');
        sb.append("market: ").append(plan.market()).append('\n');
        sb.append("events: ").append(data.eventCount()).append('\n');
        sb.append("symbols: \"").append(String.join(",", symbols.stream().map(Symbol::code).toList())).append("\"\n");
        sb.append("recording-days:\n");
        for (RecordedMarketDataSet.DayInfo d : data.days()) {
            sb.append("  - date: ").append(d.date()).append('\n');
            sb.append("    file-sha256: ").append(d.fileSha256()).append('\n');
            sb.append("    recorded-events: ").append(d.recordedEvents()).append('\n');
            sb.append("    complete: ").append(d.complete()).append('\n');
            sb.append("    truncated: ").append(d.truncated()).append('\n');
            sb.append("    segments: ").append(d.segments()).append('\n');
            sb.append("    unmarked-restarts: \"").append(String.join(",",
                    d.unmarkedRestarts().stream().map(String::valueOf).toList())).append("\"\n");
            sb.append("    inserted-gaps: ").append(d.insertedGaps()).append('\n');
        }
        sb.append("data-gaps: ").append(report.dataGaps().size()).append('\n');
        sb.append("cost-model: ").append(cost.id()).append('\n');
        sb.append("cost-table-hash: ").append(report.result().costTableHash()).append('\n');
        sb.append("execution-model: \"").append(execution.id()).append("\"\n");
        sb.append("boards: \"").append(String.join(",", plan.boards().entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).toList())).append("\"\n");
        sb.append("blacklist:\n");
        sb.append("  fingerprint: ").append(blacklist.fingerprint()).append('\n');
        sb.append("  size: ").append(blacklist.size()).append('\n');
        sb.append("benchmark: ").append(plan.benchmark().orElse("none")).append('\n');
        sb.append("seed: ").append(plan.seed()).append('\n');
        sb.append("seed-capital: \"").append(plan.seedCapital()).append("\"\n");
        sb.append("determinism: \"").append(determinism).append("\"\n");
        sb.append("detail-sha256: ").append(sha256(detail)).append('\n');
        if (!plan.selections().isEmpty()) {
            selectionInputs(sb, plan.selections(), applied);
        }
        return sb.toString();
    }

    /** inputs.yaml의 선정 칸(D-124): 등록 파일 선정 입력과 날짜·선정별 적용 기록. */
    private static void selectionInputs(StringBuilder sb, RegisteredSelections sel, List<SessionSelections.Applied> applied) {
        sb.append("selection-input:\n");
        sb.append("  daily: \"").append(sel.daily().orElse("")).append("\"\n");
        sb.append("  financials: \"").append(sel.financials().orElse("")).append("\"\n");
        sb.append("selection-days:\n");
        for (SessionSelections.Applied a : applied) {
            for (SessionSelections.Item it : a.items()) {
                sb.append("  - date: ").append(a.date()).append('\n');
                sb.append("    selection: \"").append(it.selection()).append("\"\n");
                sb.append("    status: ").append(it.status()).append('\n');
                if (it.applied()) {
                    SelectionCandidates c = it.candidates();
                    sb.append("    content-hash: ").append(it.contentHash()).append('\n');
                    sb.append("    candidates: \"").append(String.join(",", c.candidates())).append("\"\n");
                    sb.append("    not-subscribed: \"").append(String.join(",",
                            c.excludedCodes(SelectionCandidates.Exclusion.NOT_SUBSCRIBED))).append("\"\n");
                    sb.append("    blacklisted: \"").append(String.join(",",
                            c.excludedCodes(SelectionCandidates.Exclusion.BLACKLISTED))).append("\"\n");
                    sb.append("    coverage: \"").append(c.coverage()).append("\"\n");
                } else {
                    sb.append("    reason: ").append(it.reason()).append('\n');
                    sb.append("    detail: \"").append(it.detail().replace("\\", "\\\\").replace("\"", "\\\""))
                            .append("\"\n");
                }
            }
        }
    }

    public static String results(RecordingPlan plan, RecordingInput data, CostModel cost, ExecutionSettings execution,
            BlacklistSnapshot blacklist, SimulationReport report, String determinism) {
        return results(plan, data, cost, execution, blacklist, report, determinism, List.of());
    }

    /** @param applied {@link #inputs}와 같음. 비어 있지 않으면 계좌 표 뒤에 선정 표를 쓴다 */
    public static String results(RecordingPlan plan, RecordingInput data, CostModel cost, ExecutionSettings execution,
            BlacklistSnapshot blacklist, SimulationReport report, String determinism,
            List<SessionSelections.Applied> applied) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.id()).append(" 결과\n\n");
        sb.append("- 질문: ").append(plan.question()).append('\n');
        sb.append("- 기간: ").append(plan.from()).append(" ~ ").append(plan.to()).append(" (녹화일 ").append(data.days().size())
                .append("일), 시드 ").append(plan.seed()).append(", 계좌별 시드머니 ").append(plan.seedCapital()).append('\n');
        sb.append("- 데이터: `").append(data.version()).append("`, 이벤트 ").append(data.eventCount()).append(", 시세 공백 ")
                .append(report.dataGaps().size()).append('\n');
        sb.append("- 비용 `").append(cost.id()).append("`, 체결 `").append(execution.id()).append("`, 블랙리스트 ")
                .append(blacklist.size()).append("종목, 벤치마크 ").append(plan.benchmark().orElse("없음")).append('\n');
        sb.append("- 결정론 확인: ").append(determinism).append('\n');
        sb.append('\n');
        sb.append("> 비용 차감 성과다. 관측 거래일이 ").append(MetricsCalculator.MIN_SHARPE_DAYS)
                .append("일 미만이면 샤프, ").append(MetricsCalculator.MIN_CAGR_DAYS)
                .append("일 미만이면 CAGR은 판정 불가(-)로 둔다. 짧은 기간 결과로 기법을 고르지 않는다. 투자 자문이 아니다.\n\n");
        sb.append("| 계좌 | 프로필 | 관측 일수 | 총수익률 | MDD | 샤프 | CAGR | 체결 | 왕복 | 비용 | 상태 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (int i = 0; i < report.runs().size(); i++) {
            RunReport r = report.runs().get(i);
            RunResult s = r.summary();
            RunMetrics m = s.metrics();
            sb.append("| ").append(s.accountId()).append(" | ");
            r.combination().selection().ifPresent(sel -> sb.append(sel.key()).append(" x "));
            sb.append(r.profile().id()).append('@')
                    .append(r.profile().version()).append(" | ").append(r.equityCurve().size()).append(" | ");
            if (m == null) {
                sb.append("- | - | - | - | - | - | - | ");
            } else {
                sb.append(pct(m.totalReturn())).append(" | ").append(pct(m.maxDrawdown())).append(" | ")
                        .append(num(m.sharpe())).append(" | ").append(pct(m.cagr())).append(" | ").append(m.fillCount())
                        .append(" | ").append(m.roundTripCount()).append(" | ").append(m.totalCosts()).append(" | ");
            }
            sb.append(s.failureReason().isEmpty() ? s.status().name() : s.status() + " " + s.failureReason()).append(" |\n");
        }
        if (!plan.selections().isEmpty()) {
            selectionResults(sb, report, applied);
        }
        if (!report.dataGaps().isEmpty()) {
            sb.append("\n## 시세 공백\n\n");
            sb.append("| seq | 종목 | 원인 | 시작 | 끝 |\n|---:|---|---|---|---|\n");
            for (DataGap g : report.dataGaps()) {
                sb.append("| ").append(g.sequence()).append(" | ").append(g.symbol().code()).append(" | ").append(g.reason())
                        .append(" | ").append(g.gapStart()).append(" | ").append(g.gapEnd()).append(" |\n");
            }
        }
        return sb.toString();
    }

    /**
     * results.md 선정 표(D-124, ADR-012 3.5): 날짜·선정마다 상태, 그날 후보, 구독 밖({@code NOT_SUBSCRIBED})·블랙리스트
     * ({@code BLACKLISTED})로 뺀 종목, 구독 커버리지, 그 선정을 쓰는 계좌들이 막힌 신규 진입 신호 수 합.
     */
    private static void selectionResults(StringBuilder sb, SimulationReport report, List<SessionSelections.Applied> applied) {
        Map<String, Integer> blocked = new LinkedHashMap<>();
        for (RunReport r : report.runs()) {
            for (com.autotrade.sim.run.RunSelectionDay d : r.selectionDays()) {
                blocked.merge(r.combination().selectionHashOrNone() + "|" + d.selection().date(), d.blockedEntries(),
                        Integer::sum);
            }
        }
        sb.append("\n## 선정(D-124)\n\n");
        sb.append("> 그날 후보 = 선정 결과 ∩ boards − blacklist. 뺀 자리는 다음 순위로 메우지 않는다. 커버리지는 선정 종목 중 boards 안 "
                + "비율이다. 성과를 시장 전체 선정의 성과로 읽지 않는다(ADR-012 6장 R2).\n\n");
        sb.append("| 날짜 | 선정 | 상태 | 후보 | NOT_SUBSCRIBED | BLACKLISTED | 커버리지 | 막힌 신규 진입 |\n");
        sb.append("|---|---|---|---|---|---|---:|---:|\n");
        for (SessionSelections.Applied a : applied) {
            for (SessionSelections.Item it : a.items()) {
                sb.append("| ").append(a.date()).append(" | ").append(it.selection()).append(" | ");
                if (it.applied()) {
                    SelectionCandidates c = it.candidates();
                    sb.append(it.status()).append(" | ").append(list(c.candidates())).append(" | ")
                            .append(list(c.excludedCodes(SelectionCandidates.Exclusion.NOT_SUBSCRIBED))).append(" | ")
                            .append(list(c.excludedCodes(SelectionCandidates.Exclusion.BLACKLISTED))).append(" | ")
                            .append(c.coverage()).append(" | ");
                } else {
                    sb.append(it.status()).append("(").append(it.reason()).append(") | - | - | - | - | ");
                }
                sb.append(blocked.getOrDefault(it.selectionHash() + "|" + a.date(), 0)).append(" |\n");
            }
        }
    }

    private static String list(List<String> codes) {
        return codes.isEmpty() ? "-" : String.join(", ", codes);
    }

    public static StrategyProfile loadStrategy(Path root, RecordingPlan.ProfileRef ref) {
        Path file = root.resolve("strategies").resolve(ref.id()).resolve("v" + ref.version() + ".yaml");
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프로필 파일을 읽을 수 없습니다: " + file, e);
        }
        StrategyProfile p = ProfileYaml.parse(text, file.toString());
        if (!p.id().equals(ref.id()) || p.version() != ref.version()) {
            throw new IllegalArgumentException(file + ": 경로와 프로필 ID·버전이 다릅니다: " + p.id() + "@" + p.version());
        }
        return p;
    }

    private static String pct(BigDecimal v) {
        return v == null ? "-" : v.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
