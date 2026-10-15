package com.autotrade.sim.walkforward;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.risk.BlacklistEntry;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.BlacklistType;
import com.autotrade.risk.RiskChain;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.combo.CombinationPlan;
import com.autotrade.sim.combo.SnapshotInputs;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.daily.DailyBarMarketDataSet;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunSeeds;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.CalculatedSelectionSource;
import com.autotrade.sim.study.ScreeningThresholds;
import com.autotrade.sim.study.StrategyStudy;
import com.autotrade.sim.walkforward.WalkForwardResult.Candidate;
import com.autotrade.sim.walkforward.WalkForwardResult.NullSummary;
import com.autotrade.sim.walkforward.WalkForwardResult.WindowInfo;
import com.autotrade.sim.walkforward.WalkForwardWindows.Window;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import com.autotrade.strategy.catalog.EntryFunnel;
import com.autotrade.strategy.catalog.ExitLog;
import com.autotrade.strategy.catalog.ManagedStrategy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 일봉 워크포워드 판정 실험(4단계 계획 3·4장, D-071). 등록 파일 {@code study.yaml}의 후보 조합을 구간 k마다 새 계좌로
 * 학습·검증하고 대조군·±20%·슬리피지 2배를 돌려 {@link WalkForwardJudge} 판정표를 만든다.
 *
 * <p>구간마다 학습 데이터셋({@code [학습 시작 − 예열, 학습 끝]})과 검증 데이터셋({@code [검증 시작 − 예열, 검증 끝]})을 따로
 * 만든다(예열 거래일은 시세만 전략에 주고 진입·체결·평가는 없다, {@link SimulationExperimentRunner}의 {@code warmupDays}).
 * 구간 끝에 든 포지션은 마지막 거래일 종가에 팔았다고 보고 비용을 빼 왕복(WINDOW_END)으로 센다({@code settleAtWindowEnd}).
 * 데이터셋마다 가격 기준(첫 일봉)이 달라 가격 단위가 갈릴 수 있으나 계좌는 구간마다 새로 시작하므로 영향이 없다.
 * 실행기·리스크 체인·비용 모델·선정 계산은 조합 실험과 같은 코드다.
 *
 * <p>순서(결과에 영향 없음): 구간 → 학습(후보 + ±20% 변형) → 검증(후보) → 슬리피지 2배 검증 → 대조군 시드별 검증. 대조군 시드 i는
 * 실험 시드 + i이고 후보와 같은 선정 + 그 스타일 대조군 전략을 돈다. 병렬 수는 결과에 영향을 주지 않는다.
 */
public final class WalkForwardStudy {

    /** 결정론 확인 줄이 들어갈 자리. 두 실행을 비교한 뒤 바꿔 넣는다. */
    static final String DETERMINISM_PLACEHOLDER = "@@DETERMINISM@@";
    static final String INPUTS_FILE = "inputs.yaml";

    private WalkForwardStudy() {
    }

    /**
     * @param files 결과 파일(이름 → 내용). 같은 입력이면 바이트까지 같다
     * @param anyRunFailed 실패한 Run이 있는지(판정은 보류로 남는다)
     * @param deterministic 결정론 확인 결과. 확인하지 않았으면 null
     */
    public record Outcome(Map<String, String> files, boolean anyRunFailed, Boolean deterministic) {

        public Outcome {
            files = Map.copyOf(files);
        }
    }

    public static Outcome run(WalkForwardPlan plan, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        if (plan.market() != Market.KRX) {
            throw new IllegalArgumentException("일봉 워크포워드는 국내(KRX)만 지원합니다: " + plan.market());
        }
        List<Window> windows = WalkForwardWindows.compute(plan.firstTestFrom(), plan.trainYears(), plan.testYears(),
                plan.windows());
        ExchangeCalendar calendar = ExchangeCalendar.bundled(Market.KRX);
        SnapshotInputs in = SnapshotInputs.load(Market.KRX, plan.barsSnapshot(), plan.financialsSnapshot(), snapshotsRoot,
                calendar, gate, log, (first, last) -> WalkForwardWindows.checkCoverage(windows, first, last));
        LocalDate spanFrom = DailyBarMarketDataSet.warmupStart(in.days().dates(), windows.get(0).trainFrom(),
                plan.warmupDays());
        LocalDate spanTo = windows.get(windows.size() - 1).testTo();

        // 종목 이력(소속부·종류)은 마지막 일봉일 기준 한 구간으로 단순화(Q-012, 조합 실험과 같다)
        gate.check();
        Map<Symbol, InstrumentPeriod> periods = periods(in, spanFrom, spanTo);
        KrxMarketRules rules = SnapshotInputs.rules(calendar, periods);
        CostModel cost = SnapshotInputs.krCost(periods);
        CostModel stressCost = cost.withSlippage(cost.slippageRate().multiply(BigDecimal.valueOf(2)));
        if (!cost.covers(windows.get(0).trainFrom(), spanTo)) {
            throw new IllegalArgumentException("거래 구간이 비용 표 밖입니다(SIM-07): " + windows.get(0).trainFrom() + " ~ " + spanTo
                    + ", " + cost.id() + ". 예열 구간은 시세만 주므로 이 검사에 들지 않습니다");
        }
        CatalogStrategyFactory factory = new CatalogStrategyFactory(rules);
        List<Candidate> candidates = candidates(plan, repoRoot, factory);

        BlacklistSnapshot blacklist = BlacklistSnapshot.of(plan.blacklist().stream()
                .map(c -> new BlacklistEntry(Symbol.krx(c), BlacklistType.MANUAL, "실험 등록 파일 " + plan.id())).toList());
        RiskChain risk = RiskChain.builder(blacklist).build();

        log.accept("벤치마크 지수 계산(시총 가중·동일가중)");
        gate.check();
        MarketIndex index = MarketIndex.build(in.bars(), in.shares(), Market.KRX, windows.get(0).testFrom().minusDays(30),
                spanTo);

        Context ctx = new Context(plan, in, windows, candidates, rules, cost, stressCost, factory, risk, index, blacklist,
                code, log, gate, spanFrom);
        // 실제 병렬 수: 등록 파일 parallelism과 실행 명령 상한 중 작은 쪽(D-089). 결정론 기록에도 이 값을 쓴다
        int parallel = Math.min(plan.parallelism(), gate.maxParallelRuns());
        log.accept("계산 시작: 구간 " + windows.size() + ", 후보 m=" + plan.m() + ", 대조군 시드 " + plan.nullSeeds()
                + ", 병렬 " + parallel);
        Computed first = compute(ctx, parallel);
        Boolean deterministic = null;
        if (verifyDeterminism && parallel == 1) {
            log.accept("결정론 확인 생략: 실제 병렬 수가 1입니다");
        } else if (verifyDeterminism) {
            log.accept("결정론 확인: 병렬 1로 전체를 다시 실행");
            gate.check();
            Computed second = compute(ctx, 1);
            deterministic = second.files().equals(first.files());
            if (!deterministic) {
                log.accept("결정론 확인 실패: 두 실행의 결과 파일이 다릅니다");
            }
        }
        String text = deterministic == null
                ? (verifyDeterminism ? "확인 안 함(실제 병렬 수가 1이라 병렬 대 직렬 비교가 안 됨. -PmaxParallelRuns와 등록 파일"
                        + " parallelism을 2 이상으로)" : "확인 안 함(-PverifyDeterminism으로 확인)")
                : deterministic ? "같음(병렬 " + parallel + " vs 1, 판정표·상세 보고서·결과 파일 바이트 일치)" : "다름";
        Map<String, String> files = new LinkedHashMap<>();
        first.files().forEach((k, v) -> files.put(k, v.replace(DETERMINISM_PLACEHOLDER, text)));
        return new Outcome(files, first.anyRunFailed(), deterministic);
    }

    /**
     * 등록 파일 내용의 SHA-256(16진수 소문자). 줄바꿈을 LF로 맞춘 뒤 계산한다: Windows 작업 트리(Git {@code core.autocrlf})의
     * CRLF 사본과 저장소에 커밋된 LF 원본이 같은 값이 되어야 대조할 수 있다(PR #98 검토 M2).
     */
    static String studySha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 등록 파일을 읽어 실험을 돌리고 결과 파일을 등록 파일 옆에 쓴다. 멈추면 아무것도 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        Path file = experimentDir.resolve("study.yaml");
        WalkForwardPlan plan;
        String studySha256;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            plan = WalkForwardPlan.parse(text, file.toString());
            studySha256 = studySha256(text);
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 읽을 수 없습니다: " + file, e);
        }
        if (!plan.id().value().equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + plan.id());
        }
        Outcome ran = run(plan, repoRoot, snapshotsRoot, code, verifyDeterminism, log, gate);
        // 결과를 만든 등록 파일이 커밋된 등록 파일과 같은지 나중에 대조할 수 있게 남긴다(PR #80 검토 N1)
        Map<String, String> files = new LinkedHashMap<>(ran.files());
        files.computeIfPresent(INPUTS_FILE, (k, v) -> v.replaceFirst("(?m)^(experiment: .*\\n)",
                "$1study-sha256: " + studySha256 + "\n"));
        Outcome out = new Outcome(files, ran.anyRunFailed(), ran.deterministic());
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            Path target = experimentDir.resolve(f.getKey());
            try {
                Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
            }
            log.accept("작성: " + target);
        }
        return out;
    }

    // ---------------------------------------------------------------- 준비

    private record Context(WalkForwardPlan plan, SnapshotInputs in, List<Window> windows, List<Candidate> candidates,
            KrxMarketRules rules, CostModel cost, CostModel stressCost, CatalogStrategyFactory factory, RiskChain risk,
            MarketIndex index, BlacklistSnapshot blacklist, CodeVersion code, Consumer<String> log, RunGate gate,
            LocalDate spanFrom) {
    }

    private record Computed(Map<String, String> files, boolean anyRunFailed) {
    }

    private static Map<Symbol, InstrumentPeriod> periods(SnapshotInputs in, LocalDate from, LocalDate to) {
        TreeSet<Symbol> universe = new TreeSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            universe.addAll(in.bars().universe(Market.KRX, d));
        }
        Map<Symbol, InstrumentPeriod> out = new HashMap<>();
        for (Symbol s : universe) {
            List<DailyBar> bars = in.bars().bars(s, from, to);
            if (!bars.isEmpty()) {
                in.bars().instrumentOn(s, bars.get(bars.size() - 1).date()).ifPresent(p -> out.put(s, p));
            }
        }
        return out;
    }

    /** 후보마다 조합, 같은 선정 + 스타일 대조군, ±20% 변형을 만들고 검증한다(스타일·시장 불일치는 여기서 거부). */
    static List<Candidate> candidates(WalkForwardPlan plan, Path repoRoot, CatalogStrategyFactory factory) {
        Map<TradingStyle, StrategyProfile> controlProfiles = new TreeMap<>();
        for (Map.Entry<TradingStyle, CombinationPlan.Ref> e : plan.controls().entrySet()) {
            StrategyProfile p = CombinationExperiment.loadStrategy(repoRoot, e.getValue());
            if (p.style() != e.getKey()) {
                throw new IllegalArgumentException("대조군 스타일이 맞지 않습니다: 등록 " + e.getKey() + ", 프로필 "
                        + e.getValue().text() + "=" + p.style());
            }
            factory.validate(p);
            controlProfiles.put(e.getKey(), p);
        }
        Map<String, StrategyProfile> profileCache = new HashMap<>();
        Map<String, SelectionProfile> selectionCache = new HashMap<>();
        List<Candidate> out = new ArrayList<>();
        for (CombinationPlan.Entry e : plan.combinations()) {
            StrategyProfile p = profileCache.computeIfAbsent(e.strategy().text(),
                    k -> CombinationExperiment.loadStrategy(repoRoot, e.strategy()));
            factory.validate(p);
            if (p.style() != TradingStyle.SWING && p.style() != TradingStyle.POSITION) {
                throw new IllegalArgumentException("일봉 워크포워드는 SWING·POSITION 프로필만 판정합니다: " + e.text() + " ("
                        + p.style() + ")");
            }
            SelectionProfile sel = e.selection().map(r -> selectionCache.computeIfAbsent(r.text(),
                    k -> CombinationExperiment.loadSelection(repoRoot, r))).orElse(null);
            Combination combo = sel == null ? Combination.of(p) : Combination.of(sel, p); // 스타일 불일치는 여기서 거부
            StrategyProfile controlProfile = controlProfiles.get(p.style());
            if (controlProfile == null) {
                throw new IllegalArgumentException("후보 " + e.text() + "의 스타일 " + p.style()
                        + " 대조군이 controls에 없습니다(\"" + p.style() + " id@버전\")");
            }
            Combination control = sel == null ? Combination.of(controlProfile) : Combination.of(sel, controlProfile);
            String pairKey = control.selectionKeyOrNone() + " x " + controlProfile.id() + "@" + controlProfile.version();

            String key = p.parameters().get("study.key-param");
            Combination low = null;
            Combination high = null;
            String note = "";
            if (key == null) {
                note = "프로필에 study.key-param이 없어 ±20% 변형을 만들 수 없음";
            } else {
                try {
                    StrategyProfile lp = StrategyStudy.Perturbation.scale(p, key, new BigDecimal("0.8"), "k80");
                    StrategyProfile hp = StrategyStudy.Perturbation.scale(p, key, new BigDecimal("1.2"), "k120");
                    factory.validate(lp);
                    factory.validate(hp);
                    low = sel == null ? Combination.of(lp) : Combination.of(sel, lp);
                    high = sel == null ? Combination.of(hp) : Combination.of(sel, hp);
                } catch (IllegalArgumentException ex) {
                    note = "±20% 변형이 파라미터 범위 밖이라 C7 판정 불가: " + ex.getMessage();
                }
            }
            out.add(new Candidate(e.text(), combo, pairKey, control, low, high, key == null ? "" : key, note));
        }
        requireWarmup(plan, factory, out);
        return out;
    }

    /**
     * 후보·±20% 변형·대조군이 첫 판단 전에 받아야 하는 거래일 수({@link CatalogStrategyFactory#requiredHistoryDays})가
     * 등록 파일의 {@code warmup-days}보다 크면 시작하지 않는다. 모자라면 구간 앞부분에 신호가 나지 않아 학습·검증 성과와
     * C7(변형끼리 비교)이 조회 기간이 긴 쪽으로 기운다(PR #79 검토 M1). 모자란 프로필을 모두 적어 한 번에 거부한다.
     */
    static void requireWarmup(WalkForwardPlan plan, CatalogStrategyFactory factory, List<Candidate> candidates) {
        Map<String, Integer> need = new TreeMap<>();
        for (Candidate c : candidates) {
            for (Combination combo : new Combination[] {c.combination(), c.control(), c.low(), c.high()}) {
                if (combo != null) {
                    StrategyProfile p = combo.profile();
                    need.putIfAbsent(p.id() + "@" + p.version(), factory.requiredHistoryDays(p));
                }
            }
        }
        List<String> lacking = need.entrySet().stream().filter(x -> x.getValue() > plan.warmupDays())
                .map(x -> x.getKey() + "=" + x.getValue() + "일").toList();
        if (!lacking.isEmpty()) {
            int max = need.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            throw new IllegalArgumentException("warmup-days " + plan.warmupDays() + "일이 프로필의 예열 필요량보다 작습니다: "
                    + String.join(", ", lacking) + ". warmup-days를 " + max + " 이상으로 등록하십시오");
        }
    }

    // ---------------------------------------------------------------- 계산

    private static Computed compute(Context c, int parallelism) {
        WalkForwardPlan plan = c.plan();
        CalculatedSelectionSource selections = c.in().newSelectionSource();
        List<Window> windows = c.windows();
        int w = plan.warmupDays();

        Map<String, List<WindowRun>> trainBy = new HashMap<>();
        Map<String, List<WindowRun>> valBy = new HashMap<>();
        Map<String, List<WindowRun>> stressBy = new HashMap<>();
        // 대조군 쌍 → 시드 i(0부터) → 구간별 Run
        Map<String, List<List<WindowRun>>> nullBy = new TreeMap<>();
        for (Candidate cand : c.candidates()) {
            nullBy.computeIfAbsent(cand.pairKey(), k -> {
                List<List<WindowRun>> l = new ArrayList<>();
                for (int i = 0; i < plan.nullSeeds(); i++) {
                    l.add(new ArrayList<>());
                }
                return l;
            });
        }
        Map<String, Combination> controlByPair = new TreeMap<>();
        c.candidates().forEach(cand -> controlByPair.putIfAbsent(cand.pairKey(), cand.control()));
        List<Combination> candidateCombos = c.candidates().stream().map(Candidate::combination).toList();
        List<Combination> trainCombos = new ArrayList<>(candidateCombos);
        for (Candidate cand : c.candidates()) {
            if (cand.low() != null) {
                trainCombos.add(cand.low());
                trainCombos.add(cand.high());
            }
        }
        List<Combination> controlCombos = List.copyOf(controlByPair.values());

        StringBuilder runs = new StringBuilder("phase\twindow\tnull_seed\tselection\tstrategy\tstatus\tfinal_equity"
                + "\ttotal_return\tmax_drawdown\tsharpe\tprofit_factor\tround_trips\ttrading_days\ttotal_costs"
                + "\tsignals\tentries\tlimit_blocked\tsize_blocked\tlimit_days\tcombination_hash\tfailure\n");
        StringBuilder detail = new StringBuilder();
        Map<String, Probe> probes = new HashMap<>(); // Run ID → 구간 안 진입 깔때기·청산 사유
        List<WindowInfo> infos = new ArrayList<>();
        boolean anyFailed = false;

        for (Window win : windows) {
            c.gate().check();
            int k = win.number();
            c.log().accept("구간 " + k + "/" + windows.size() + " 학습 " + win.trainFrom() + " ~ " + win.trainTo()
                    + ", 검증 " + win.testFrom() + " ~ " + win.testTo());
            LocalDate trainWarm = DailyBarMarketDataSet.warmupStart(c.in().days().dates(), win.trainFrom(), w);
            LocalDate testWarm = DailyBarMarketDataSet.warmupStart(c.in().days().dates(), win.testFrom(), w);

            DailyBarMarketDataSet trainData = DailyBarMarketDataSet.of(c.in().bars(), c.in().bars().snapshotId(),
                    Market.KRX, c.in().calendar().zone(), trainWarm, win.trainTo());
            int trainDays = (int) trainData.tradingDays().stream()
                    .filter(d -> !d.isBefore(win.trainFrom()) && !d.isAfter(win.trainTo())).count();
            for (SimulationReport rep : runAll(c, trainData, c.cost(), trainCombos, plan.seed(), win.trainFrom(),
                    win.trainTo(), "W" + k + "-TRN", parallelism, selections, probes)) {
                for (RunReport r : rep.runs()) {
                    WindowRun wr = WindowRun.of(k, r, false, probes.get(r.summary().runId()));
                    anyFailed |= !wr.completed();
                    trainBy.computeIfAbsent(r.summary().combinationHash(), x -> new ArrayList<>()).add(wr);
                    row(runs, phaseOfTrain(r, candidateCombos), k, "", r, probes.get(r.summary().runId()).funnel());
                }
            }
            String trainVersion = trainData.version();
            trainData = null; // 다음 데이터셋 전에 메모리를 돌려준다

            DailyBarMarketDataSet testData = DailyBarMarketDataSet.of(c.in().bars(), c.in().bars().snapshotId(),
                    Market.KRX, c.in().calendar().zone(), testWarm, win.testTo());
            List<LocalDate> testDays = testData.tradingDays().stream()
                    .filter(d -> !d.isBefore(win.testFrom()) && !d.isAfter(win.testTo())).toList();
            detail.append("## 구간 ").append(k).append(" 검증 ").append(win.testFrom()).append(" ~ ").append(win.testTo())
                    .append(" (기본 비용, 데이터셋 ").append(testData.version()).append(")\n");
            for (SimulationReport rep : runAll(c, testData, c.cost(), candidateCombos, plan.seed(), win.testFrom(),
                    win.testTo(), "W" + k + "-VAL", parallelism, selections, probes)) {
                detail.append(ReportWriter.render(rep)).append('\n');
                for (RunReport r : rep.runs()) {
                    WindowRun wr = WindowRun.of(k, r, true, probes.get(r.summary().runId()));
                    anyFailed |= !wr.completed();
                    valBy.computeIfAbsent(r.summary().combinationHash(), x -> new ArrayList<>()).add(wr);
                    row(runs, "VALIDATION", k, "", r, probes.get(r.summary().runId()).funnel());
                }
            }
            for (SimulationReport rep : runAll(c, testData, c.stressCost(), candidateCombos, plan.seed(), win.testFrom(),
                    win.testTo(), "W" + k + "-STR", parallelism, selections, probes)) {
                for (RunReport r : rep.runs()) {
                    WindowRun wr = WindowRun.of(k, r, false, probes.get(r.summary().runId()));
                    anyFailed |= !wr.completed();
                    stressBy.computeIfAbsent(r.summary().combinationHash(), x -> new ArrayList<>()).add(wr);
                    row(runs, "STRESS", k, "", r, probes.get(r.summary().runId()).funnel());
                }
            }
            for (int i = 1; i <= plan.nullSeeds(); i++) {
                c.gate().check();
                for (SimulationReport rep : runAll(c, testData, c.cost(), controlCombos, plan.seed() + i, win.testFrom(),
                        win.testTo(), "W" + k + "-N" + i, parallelism, selections, probes)) {
                    for (RunReport r : rep.runs()) {
                        WindowRun wr = WindowRun.of(k, r, false, probes.get(r.summary().runId()));
                        anyFailed |= !wr.completed(); // 대조군 실패도 종료 코드 2(PR #79 architect R2)
                        String pair = pairOf(r, controlByPair);
                        nullBy.get(pair).get(i - 1).add(wr);
                        row(runs, "NULL", k, Integer.toString(i), r, probes.get(r.summary().runId()).funnel());
                    }
                }
                if (i % 50 == 0) {
                    c.log().accept("  구간 " + k + " 대조군 시드 " + i + "/" + plan.nullSeeds());
                }
            }
            infos.add(new WindowInfo(win, trainWarm, testWarm, trainDays, testDays.size(),
                    testDays.isEmpty() ? null : testDays.get(0), testDays.isEmpty() ? null : testDays.get(testDays.size() - 1),
                    trainVersion, testData.version(), testData.symbols().size()));
        }

        // 판정
        List<BigDecimal> idxCapWin = new ArrayList<>();
        List<BigDecimal> idxEqWin = new ArrayList<>();
        for (Window win : windows) {
            idxCapWin.add(c.index().capReturn(win.testFrom(), win.testTo()));
            idxEqWin.add(c.index().equalReturn(win.testFrom(), win.testTo()));
        }
        BigDecimal idxCap = MarketIndex.chain(idxCapWin);
        BigDecimal idxEq = MarketIndex.chain(idxEqWin);
        ScreeningThresholds t = ScreeningThresholds.defaults();
        LocalDate from = windows.get(0).testFrom();
        LocalDate to = windows.get(windows.size() - 1).testTo();

        Map<String, NullSummary> nullSummaries = new TreeMap<>();
        Map<String, List<BigDecimal>> nullPerTrip = new TreeMap<>();
        for (Map.Entry<String, List<List<WindowRun>>> e : nullBy.entrySet()) {
            List<BigDecimal> values = new ArrayList<>();
            int failed = 0;
            int zeroTrip = 0;
            EntryFunnel.Totals funnel = EntryFunnel.Totals.ZERO;
            long holdDays = 0;
            long holdTrips = 0;
            ExitMix exits = ExitMix.ZERO;
            for (List<WindowRun> perSeed : e.getValue()) {
                for (WindowRun wr : perSeed) {
                    funnel = funnel.plus(wr.funnel());
                }
                if (perSeed.size() != windows.size() || !perSeed.stream().allMatch(WindowRun::completed)) {
                    failed++;
                    continue;
                }
                for (WindowRun wr : perSeed) {
                    exits = exits.plus(wr.exits());
                    for (RoundTrip trip : wr.roundTrips()) {
                        holdDays += ChronoUnit.DAYS.between(trip.entryDate(), trip.exitDate());
                        holdTrips++;
                    }
                }
                BigDecimal v = Pooled.perTrip(plan.seedCapital(), perSeed);
                if (v != null) {
                    values.add(v);
                } else {
                    zeroTrip++; // 왕복 0건: 왕복당 순손익을 정의할 수 없어 분포와 N에서 뺀다
                }
            }
            values.sort(BigDecimal::compareTo);
            nullPerTrip.put(e.getKey(), values);
            nullSummaries.put(e.getKey(), new NullSummary(e.getKey(), values.size(), failed, zeroTrip,
                    values.isEmpty() ? null : values.get(0), values.isEmpty() ? null : values.get(values.size() / 2),
                    values.isEmpty() ? null : values.get(values.size() - 1), funnel,
                    holdTrips == 0 ? null : BigDecimal.valueOf(holdDays).divide(BigDecimal.valueOf(holdTrips), 6,
                            RoundingMode.HALF_EVEN), exits));
        }
        List<WalkForwardJudge.Judgement> judgements = new ArrayList<>();
        for (Candidate cand : c.candidates()) {
            String h = cand.combination().combinationHash();
            WalkForwardJudge.Evidence ev = new WalkForwardJudge.Evidence(valBy.getOrDefault(h, List.of()),
                    stressBy.getOrDefault(h, List.of()), trainBy.getOrDefault(h, List.of()),
                    cand.low() == null ? null : trainBy.getOrDefault(cand.low().combinationHash(), List.of()),
                    cand.high() == null ? null : trainBy.getOrDefault(cand.high().combinationHash(), List.of()),
                    cand.perturbNote(), cand.perturbKey(), nullPerTrip.get(cand.pairKey()), idxCapWin, idxCap);
            judgements.add(WalkForwardJudge.evaluate(ev, plan.m(), plan.seedCapital(), t, from, to));
        }

        String costTableHash = c.cost().tableHash(windows.get(0).trainFrom(), to);
        WalkForwardResult result = new WalkForwardResult(plan, c.in().selectionData(), c.in().days(), c.in().shares(),
                windows, c.candidates(), judgements,
                c.candidates().stream().map(x -> valBy.getOrDefault(x.combination().combinationHash(), List.<WindowRun>of())).toList(),
                c.index(), idxCapWin, idxEqWin, idxCap, idxEq, c.cost(),
                c.stressCost(), costTableHash, ExecutionSettings.defaults(), nullSummaries, infos,
                c.code().commit() + (c.code().dirty() ? " dirty" : ""), c.blacklist().fingerprint(), c.blacklist().size());
        Map<String, String> files = new LinkedHashMap<>();
        files.put(INPUTS_FILE, WalkForwardReport.inputs(result, c.spanFrom()));
        files.put("results.md", WalkForwardReport.results(result, t));
        files.put("runs.tsv", runs.toString());
        files.put("windows.tsv", WalkForwardReport.windowsTsv(result));
        files.put("detail.txt", WalkForwardReport.detail(result, detail.toString()));
        return new Computed(files, anyFailed);
    }

    private static String phaseOfTrain(RunReport r, List<Combination> candidateCombos) {
        String h = r.summary().combinationHash();
        for (Combination cc : candidateCombos) {
            if (cc.combinationHash().equals(h)) {
                return "TRAIN";
            }
        }
        return r.profile().id().endsWith("-k80") ? "PERTURB-LOW" : "PERTURB-HIGH";
    }

    private static String pairOf(RunReport r, Map<String, Combination> controlByPair) {
        for (Map.Entry<String, Combination> e : controlByPair.entrySet()) {
            if (e.getValue().combinationHash().equals(r.summary().combinationHash())) {
                return e.getKey();
            }
        }
        throw new IllegalStateException("대조군 쌍을 찾을 수 없습니다: " + r.summary().runId());
    }

    /** 한 번의 실행기 호출(조합이 128개를 넘으면 순서대로 나눠 호출. 조합은 서로 독립이라 결과가 같다). */
    private static List<SimulationReport> runAll(Context c, DailyBarMarketDataSet data, CostModel costModel,
            List<Combination> combos, long seed, LocalDate from, LocalDate to, String idSuffix, int parallelism,
            CalculatedSelectionSource selections, Map<String, Probe> probes) {
        List<SimulationReport> out = new ArrayList<>();
        int max = SimulationExperimentRunner.MAX_COMBINATIONS;
        for (int start = 0, chunk = 0; start < combos.size(); start += max, chunk++) {
            List<Combination> part = combos.subList(start, Math.min(combos.size(), start + max));
            ExperimentId id = new ExperimentId(c.plan().id().value() + "-" + idSuffix + (chunk == 0 ? "" : "-C" + (chunk + 1)));
            ExperimentSpec spec = new ExperimentSpec(id, from, to, seed, c.plan().seedCapital(), costModel.id(),
                    data.version(), ClockMode.REPLAY, part);
            FunnelCapture capture = new FunnelCapture(c.factory());
            SimulationReport rep = new SimulationExperimentRunner(data, c.rules(), costModel, capture, c.risk(),
                    ExecutionSettings.defaults(), c.code(), Math.min(parallelism, part.size()), null, selections, c.gate(),
                    c.plan().warmupDays(), true).runDetailed(spec);
            for (RunReport r : rep.runs()) {
                probes.put(r.summary().runId(), capture.probe(r, seed, from, to));
            }
            out.add(rep);
        }
        return out;
    }

    /** Run 하나의 측정: 구간 안 진입 깔때기와 왕복 청산 사유 분포(둘 다 판정에 쓰지 않음). */
    record Probe(EntryFunnel.Totals funnel, ExitMix exits) {
        static final Probe ZERO = new Probe(EntryFunnel.Totals.ZERO, ExitMix.ZERO);
    }

    /**
     * 실행기에 넘기는 전략 팩토리 래퍼: Run마다 만든 전략의 진입 깔때기와 청산 사유 기록만 붙잡는다(전략 자체는 붙잡지 않아
     * Run이 끝나면 시세 상태를 놓아준다). Run은 (프로필 해시, Run 시드)로 찾는다. 한 실행기 호출 안에서 조합은 서로 달라 Run
     * 시드가 겹치지 않는다({@link RunSeeds}).
     */
    static final class FunnelCapture implements StrategyFactory {
        private record Measures(EntryFunnel funnel, ExitLog exits) {
        }

        private final StrategyFactory factory;
        /** 측정하지 않는 전략(ManagedStrategy 밖)은 빈 Optional로 넣어 "못 찾음"과 구별한다. */
        private final Map<String, Optional<Measures>> byRun = new ConcurrentHashMap<>();

        FunnelCapture(StrategyFactory factory) {
            this.factory = factory;
        }

        @Override
        public Strategy create(StrategyProfile profile, long runSeed) {
            Strategy s = factory.create(profile, runSeed);
            byRun.put(profile.profileHash() + "|" + runSeed,
                    s instanceof ManagedStrategy m ? Optional.of(new Measures(m.entryFunnel(), m.exitLog())) : Optional.empty());
            return s;
        }

        /**
         * Run의 측정. 전략을 만들기 전에 실패한 Run은 {@link Probe#ZERO}, 측정하지 않는 전략은 깔때기 0과 청산 사유 "미상"
         * (구간 끝 정리 왕복은 그대로 센다). 완료된 Run을 잇지 못하면 예외로 멈춘다.
         */
        Probe probe(RunReport r, long specSeed, LocalDate from, LocalDate to) {
            String key = r.profile().profileHash() + "|" + RunSeeds.derive(specSeed, r.combination());
            Optional<Measures> f = byRun.get(key);
            if (f == null) {
                if (r.summary().status() != RunStatus.COMPLETED) {
                    return Probe.ZERO; // 전략을 만들기 전에 실패한 Run
                }
                // Run 시드 파생이 바뀌면 표가 소리 없이 0이 되지 않게 멈춘다(PR #79 architect R6)
                throw new IllegalStateException("진입 깔때기를 Run과 잇지 못했습니다: " + r.summary().runId() + " (" + key + ")");
            }
            return new Probe(f.map(x -> x.funnel().totals(from, to)).orElse(EntryFunnel.Totals.ZERO),
                    ExitMix.of(r.roundTrips(), r.windowEndLots(), f.map(Measures::exits)));
        }
    }

    private static void row(StringBuilder sb, String phase, int window, String nullSeed, RunReport r,
            EntryFunnel.Totals f) {
        var s = r.summary();
        var m = s.metrics();
        sb.append(phase).append('\t').append(window).append('\t').append(nullSeed).append('\t')
                .append(r.combination().selectionKeyOrNone()).append('\t').append(r.profile().id()).append('@')
                .append(r.profile().version()).append('\t').append(s.status());
        if (m == null) {
            sb.append("\t\t\t\t\t\t\t\t");
        } else {
            sb.append('\t').append(m.finalEquity().amount().toPlainString()).append('\t').append(plain(m.totalReturn()))
                    .append('\t').append(plain(m.maxDrawdown())).append('\t').append(plain(m.sharpe())).append('\t')
                    .append(plain(m.profitFactor())).append('\t').append(m.roundTripCount()).append('\t')
                    .append(r.equityCurve().size()).append('\t').append(m.totalCosts().amount().toPlainString());
        }
        sb.append('\t').append(f.signals()).append('\t').append(f.entries()).append('\t').append(f.limitBlocked())
                .append('\t').append(f.sizeBlocked()).append('\t').append(f.limitDays());
        sb.append('\t').append(s.combinationHash()).append('\t').append(s.failureReason().replace('\t', ' ')).append('\n');
    }

    static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
