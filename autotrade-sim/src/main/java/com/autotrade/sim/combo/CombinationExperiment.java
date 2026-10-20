package com.autotrade.sim.combo;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.daily.SnapshotDailyBarSource;
import com.autotrade.marketdata.daily.SnapshotListedShares;
import com.autotrade.marketdata.daily.SnapshotManifest;
import com.autotrade.marketdata.financial.SnapshotFinancials;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.calc.SnapshotSelectionData;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionProfileYaml;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.daily.DailyBarMarketDataSet;
import com.autotrade.sim.daily.SnapshotTradingDays;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.ResultBundle;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.CalculatedSelectionSource;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import com.autotrade.strategy.profile.ProfileYaml;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/**
 * 수집 스냅샷(ADR-008) 위에서 선정 × 매매지표 조합 실험(STR-17, ADR-007 2.4·2.7 실험실 쪽, 조합 실험 절차)을 돌린다(D-061).
 *
 * <p>이어 붙이는 것: 일봉 스냅샷 → {@link DailyBarMarketDataSet}(일봉 해상도 리플레이), 일봉·상장주식수·재무 스냅샷 →
 * {@link SnapshotSelectionData} → {@link SelectionCalculator} → {@link CalculatedSelectionSource}(날짜별 선정), 그리고
 * 기존 {@link SimulationExperimentRunner}. 계좌마다 실전과 같은 {@link RiskChain}(등록 파일의 제외 종목 스냅샷)을 통과한다.
 *
 * <p>국내(KRX)만 받는다. 미국 일봉 수집기는 아직 없다(D-056 후속).
 */
public final class CombinationExperiment {

    private CombinationExperiment() {
    }

    /**
     * 실험 결과와 결과 폴더에 쓸 파일(이름 → 내용, 같은 입력이면 바이트까지 같다).
     *
     * @param selectionResults 실험이 쓴 선정 결과(선정 프로필 키 순, 그 안은 선정 기준일 순). 실험실 화면이 선정 결과 본문을
     *     보여 주는 데 쓴다({@link ResultBundle}, D-062)
     */
    public record Outcome(ExperimentSpec spec, SimulationReport report, List<SelectionResult> selectionResults,
            Map<String, String> files) {

        public Outcome {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(report, "report");
            selectionResults = List.copyOf(selectionResults);
            files = Map.copyOf(files);
        }
    }

    /**
     * @param repoRoot 저장소 루트({@code strategies/}, {@code selections/}가 있는 곳)
     * @param snapshotsRoot 수집 스냅샷 폴더({@code <설정 폴더>/snapshots}). 그 아래 {@code krx-daily/<ID>/},
     *     {@code dart-financials/<ID>/}를 읽는다
     * @param verifyDeterminism true면 병렬 수 1로 한 번 더 돌려 상세 보고서가 같은지 본다(조합 실험 절차 1장 6번)
     * @throws IllegalArgumentException 등록 파일과 입력이 맞지 않을 때(아무 Run도 시작하지 않는다)
     */
    public static Outcome run(CombinationPlan plan, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log) {
        return run(plan, repoRoot, snapshotsRoot, code, verifyDeterminism, log, RunGate.NONE);
    }

    /**
     * @param gate 정지 확인(D-063). 스냅샷을 읽기 전, 결정론 확인 실행 전, 그리고 실행기가 Run 시작 전·거래일마다 묻는다
     * @throws ExperimentStoppedException 문이 멈추라고 했을 때(결과 없음)
     */
    public static Outcome run(CombinationPlan plan, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        if (plan.market() != Market.KRX) {
            throw new IllegalArgumentException("일봉 조합 실험은 국내(KRX)만 지원합니다: " + plan.market());
        }
        ExchangeCalendar calendar = ExchangeCalendar.bundled(Market.KRX);
        SnapshotInputs in = SnapshotInputs.load(Market.KRX, plan.barsSnapshot(), plan.financialsSnapshot(),
                plan.disclosuresSnapshot(), snapshotsRoot,
                calendar, gate, log, (first, last) -> {
                    if (plan.from().isBefore(first) || plan.to().isAfter(last)) {
                        throw new IllegalArgumentException("실험 기간이 스냅샷 범위 밖입니다: " + plan.from() + " ~ " + plan.to()
                                + ", 스냅샷 " + first + " ~ " + last);
                    }
                });
        SnapshotDailyBarSource bars = in.bars();
        SnapshotSelectionData selectionData = in.selectionData();
        SnapshotTradingDays.Derived days = in.days();
        CalculatedSelectionSource selections = new CalculatedSelectionSource(new SelectionCalculator(selectionData));

        log.accept("일봉 → 리플레이 이벤트: " + plan.from() + " ~ " + plan.to());
        DailyBarMarketDataSet data = DailyBarMarketDataSet.of(bars, bars.snapshotId(), Market.KRX, calendar.zone(),
                plan.from(), plan.to());
        gate.check();
        log.accept("종목 " + data.symbols().size() + ", 거래일 " + data.tradingDays().size() + ", 이벤트 "
                + data.events().size() + ", 권리 변동으로 이은 종목 " + data.adjustedSymbols().size());

        Map<Symbol, InstrumentPeriod> periods = new HashMap<>();
        data.lastBarDates().forEach((s, d) -> bars.instrumentOn(s, d).ifPresent(p -> periods.put(s, p)));
        KrxMarketRules rules = SnapshotInputs.rules(calendar, periods);
        CostModel cost = SnapshotInputs.krCost(periods);
        CatalogStrategyFactory factory = new CatalogStrategyFactory(rules);

        List<Combination> combinations = new ArrayList<>();
        Map<String, SelectionProfile> selectionCache = new LinkedHashMap<>();
        Map<String, StrategyProfile> profileCache = new LinkedHashMap<>();
        for (CombinationPlan.Entry e : plan.combinations()) {
            StrategyProfile p = profileCache.computeIfAbsent(e.strategy().text(), k -> loadStrategy(repoRoot, e.strategy()));
            factory.validate(p);
            if (e.selection().isPresent()) {
                SelectionProfile s = selectionCache.computeIfAbsent(e.selection().get().text(),
                        k -> loadSelection(repoRoot, e.selection().get()));
                combinations.add(Combination.of(s, p));
            } else {
                combinations.add(Combination.of(p));
            }
        }

        List<BlacklistEntry> black = plan.blacklist().stream()
                .map(c -> new BlacklistEntry(Symbol.krx(c), BlacklistType.MANUAL, "실험 등록 파일 " + plan.id())).toList();
        BlacklistSnapshot blacklist = BlacklistSnapshot.of(black);
        RiskChain risk = RiskChain.builder(blacklist).build();
        Symbol benchmark = plan.benchmark().map(Symbol::krx).orElse(null);
        ExperimentSpec spec = new ExperimentSpec(plan.id(), plan.from(), plan.to(), plan.seed(), plan.seedCapital(),
                cost.id(), data.version(), ClockMode.REPLAY, combinations);
        ExecutionSettings execution = ExecutionSettings.defaults();

        // 실제 병렬 수: 등록 파일 parallelism과 실행 명령 상한 중 작은 쪽(D-089). 결정론 기록에도 이 값을 쓴다
        int parallel = Math.min(plan.parallelism(), gate.maxParallelRuns());
        log.accept("조합 " + combinations.size() + "개 실행(병렬 " + parallel + ")");
        SimulationReport report = new SimulationExperimentRunner(data, rules, cost, factory, risk, execution, code,
                parallel, benchmark, selections, gate).runDetailed(spec);
        String detail = ReportWriter.render(report);
        String determinism = "확인 안 함(--verify-determinism으로 확인)";
        if (verifyDeterminism && parallel == 1) {
            determinism = "확인 안 함(실제 병렬 수가 1이라 병렬 대 직렬 비교가 안 됨. -PmaxParallelRuns와 등록 파일 parallelism을 2 이상으로)";
            log.accept("결정론 확인 생략: 실제 병렬 수가 1입니다");
        } else if (verifyDeterminism) {
            log.accept("결정론 확인: 병렬 1로 다시 실행");
            gate.check();
            CalculatedSelectionSource again = new CalculatedSelectionSource(new SelectionCalculator(selectionData));
            String second = ReportWriter.render(new SimulationExperimentRunner(data, rules, cost, factory, risk, execution,
                    code, 1, benchmark, again, gate).runDetailed(spec));
            determinism = second.equals(detail) ? "같음(병렬 " + parallel + " vs 1, 상세 보고서 바이트 일치)" : "다름";
            if (!second.equals(detail)) {
                log.accept("결정론 확인 실패: 두 실행의 상세 보고서가 다릅니다");
            }
        }

        CombinationReport.Context ctx = new CombinationReport.Context(plan, data, selectionData, days, cost, execution,
                blacklist, selections, determinism);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("inputs.yaml", CombinationReport.inputs(ctx, report));
        files.put("results.md", CombinationReport.results(ctx, report));
        files.put("runs.tsv", CombinationReport.runsTsv(report));
        files.put("selection-days.tsv", CombinationReport.selectionDaysTsv(ctx, report));
        files.put("detail.txt", detail);
        Map<String, String> hashByKey = new TreeMap<>();
        selectionCache.values().forEach(p -> hashByKey.put(p.key(), p.selectionHash()));
        List<SelectionResult> used = new ArrayList<>();
        hashByKey.values().forEach(hash -> used.addAll(selections.results(hash).values()));
        return new Outcome(spec, report, used, files);
    }

    /**
     * 등록 파일을 읽어 실험을 돌리고 결과 파일을 등록 파일 옆에 쓴다. 실험실 화면용 결과 묶음 {@value ResultBundle#FILE_NAME}
     * (gzip, 커밋하지 않음, D-062)도 함께 쓴다.
     */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log) {
        return runAndWrite(experimentDir, repoRoot, snapshotsRoot, code, verifyDeterminism, log, RunGate.NONE);
    }

    /**
     * {@link #runAndWrite(Path, Path, Path, CodeVersion, boolean, Consumer)}에 정지 확인(D-063)을 더한 것. 멈추면
     * {@link ExperimentStoppedException}을 던지고 결과 파일을 하나도 쓰지 않는다(이전 실행 결과는 그대로 남는다).
     */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        Path file = experimentDir.resolve("experiment.yaml");
        CombinationPlan plan;
        try {
            plan = CombinationPlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 읽을 수 없습니다: " + file, e);
        }
        if (!plan.id().value().equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + plan.id());
        }
        Outcome out = run(plan, repoRoot, snapshotsRoot, code, verifyDeterminism, log, gate);
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            Path target = experimentDir.resolve(f.getKey());
            try {
                Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
            }
            log.accept("작성: " + target);
        }
        // 쓰다 멈춘 파일을 실험실이 읽지 않도록 임시 파일에 다 쓴 뒤 바꿔 넣는다
        Path bundle = experimentDir.resolve(ResultBundle.FILE_NAME);
        Path tmp = experimentDir.resolve(ResultBundle.FILE_NAME + ".tmp");
        try {
            try (Writer w = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(tmp), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
                ResultBundle.write(out.spec(), out.report(), out.selectionResults(), w);
            }
            Files.move(tmp, bundle, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("결과 묶음을 쓸 수 없습니다: " + bundle, e);
        }
        log.accept("작성: " + bundle + " (실험실 화면에서 불러오기)");
        return out;
    }

    /** 소속부 → 비용 표 구분. "KOSDAQ/우량기업부"처럼 세부 소속부가 붙으면 앞부분을 쓴다(ADR-008 2.3). */
    public static KrxBoard board(InstrumentPeriod p, Symbol s) {
        if (p == null) {
            throw new IllegalStateException("종목 이력이 없어 소속부를 알 수 없습니다: " + s);
        }
        String head = p.board().split("/", 2)[0];
        try {
            return KrxBoard.valueOf(head);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("비용 표에 없는 소속부입니다: " + s + " " + p.board(), e);
        }
    }

    public static StrategyProfile loadStrategy(Path root, CombinationPlan.Ref ref) {
        Path file = root.resolve("strategies").resolve(ref.id()).resolve("v" + ref.version() + ".yaml");
        StrategyProfile p = ProfileYaml.parse(read(file), file.toString());
        if (!p.id().equals(ref.id()) || p.version() != ref.version()) {
            throw new IllegalArgumentException(file + ": 경로와 프로필 ID·버전이 다릅니다: " + p.id() + "@" + p.version());
        }
        return p;
    }

    public static SelectionProfile loadSelection(Path root, CombinationPlan.Ref ref) {
        Path file = root.resolve("selections").resolve(ref.id()).resolve("v" + ref.version() + ".yaml");
        SelectionProfile s = SelectionProfileYaml.parse(read(file), file.toString());
        if (!s.id().equals(ref.id()) || s.version() != ref.version()) {
            throw new IllegalArgumentException(file + ": 경로와 선정 프로필 ID·버전이 다릅니다: " + s.key());
        }
        return s;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("프로필 파일을 읽을 수 없습니다: " + file, e);
        }
    }
}
