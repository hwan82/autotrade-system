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
import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.metrics.MetricsCalculator;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
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
import java.util.TreeSet;
import java.util.function.Consumer;

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
        log.accept("프로필 " + combinations.size() + "개 실행(병렬 " + parallel + ")");
        SimulationReport report = new SimulationExperimentRunner(data, rules, cost, factory, risk, execution, code,
                parallel, benchmark, null, gate).runDetailed(spec);
        String detail = ReportWriter.render(report);
        String determinism = "확인 안 함(--verify-determinism으로 확인)";
        if (verifyDeterminism && parallel == 1) {
            determinism = "확인 안 함(실제 병렬 수가 1이라 병렬 대 직렬 비교가 안 됨. -PmaxParallelRuns와 등록 파일 parallelism을 2 이상으로)";
            log.accept("결정론 확인 생략: 실제 병렬 수가 1입니다");
        } else if (verifyDeterminism) {
            log.accept("결정론 확인: 병렬 1로 다시 실행");
            gate.check();
            String second = ReportWriter.render(new SimulationExperimentRunner(data, rules, cost, factory, risk,
                    execution, code, 1, benchmark, null, gate).runDetailed(spec));
            determinism = second.equals(detail)
                    ? "같음(병렬 " + parallel + " vs 1, 상세 보고서 바이트 일치)" : "다름";
            if (!second.equals(detail)) {
                log.accept("결정론 확인 실패: 두 실행의 상세 보고서가 다릅니다");
            }
        }

        Map<String, String> files = new LinkedHashMap<>();
        files.put("inputs.yaml", inputs(plan, data, symbols, cost, execution, blacklist, report, determinism, detail));
        files.put("results.md", results(plan, data, cost, execution, blacklist, report, determinism));
        files.put("detail.txt", detail);
        return new Outcome(spec, report, data, files);
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
     * 쓴다(D-075 4번).
     */
    public static Optional<BigDecimal> offStockTick(MarketRules rules, MarketEvent e) {
        List<BigDecimal> prices = new ArrayList<>();
        if (e instanceof TradeTick t) {
            prices.add(t.price());
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
        Outcome out = run(plan, repoRoot, recordingsRoot, code, verifyDeterminism, log, gate);
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

    public static String inputs(RecordingPlan plan, RecordingInput data, TreeSet<Symbol> symbols, CostModel cost,
            ExecutionSettings execution, BlacklistSnapshot blacklist, SimulationReport report, String determinism,
            String detail) {
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
        return sb.toString();
    }

    public static String results(RecordingPlan plan, RecordingInput data, CostModel cost, ExecutionSettings execution,
            BlacklistSnapshot blacklist, SimulationReport report, String determinism) {
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
            sb.append("| ").append(s.accountId()).append(" | ").append(r.profile().id()).append('@')
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
