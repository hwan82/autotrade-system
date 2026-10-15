package com.autotrade.sim.study;

import com.autotrade.domain.Money;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.synthetic.DayRegime;
import com.autotrade.sim.synthetic.SymbolSpec;
import com.autotrade.sim.synthetic.SyntheticKrxRules;
import com.autotrade.sim.synthetic.SyntheticMarket;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import com.autotrade.strategy.profile.ProfileYaml;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 첫 다중 전략 비교 실험(EXP-20261008-STR01)을 합성 시세로 돌리고 {@code experiments/<실험ID>/}에 결과 표를 쓴다.
 *
 * <p>실행: {@code ./gradlew :autotrade-app-experiment:runStrategyStudy} (커밋·dirty 여부는 Gradle이 git에서 넘긴다).
 * 인자: {@code <저장소 루트> <커밋> <dirty true|false>}. 프로세스 진입점은 {@code com.autotrade.app.experiment.ExperimentMain}이고
 * 장 시간 보호와 감시의 정지 요청 파일을 {@link RunGate}로 넣는다(D-063). 이 클래스에는 {@code main}이 없다.
 * 종료 코드: 0 완료, 3 취소 요청으로 멈춤(D-089), 4 정지 확인으로 멈춤(결과 파일을 쓰지 않음).
 *
 * <p>실험 설계는 아래 상수로 고정(사전 등록)하고 {@code experiment.yaml}로도 남긴다. 결과를 본 뒤 이 값을 바꿔 같은 데이터로
 * 다시 평가하지 않는다. 바꾸려면 새 실험 ID로 한다.
 */
public final class StudyMain {

    public static final ExperimentId ID = new ExperimentId("EXP-20261008-STR01");
    static final long SEED = 20261008L;
    static final LocalDate START = LocalDate.of(2026, 3, 2);
    static final int DAYS = 40;
    static final int TRAIN_DAYS = 24;
    static final int TREND_PER_MILLE = 350;
    static final int NULL_SEEDS = 20;
    static final List<Duration> LATENCIES = List.of(
            Duration.ZERO, Duration.ofMillis(500), Duration.ofMillis(1500), Duration.ofMillis(2000));
    /** 사전 등록한 프로필 {@code ID@버전}(계좌 번호 순). 버전 파일은 고치지 않으므로(ADR-005 4장) 버전까지 고정한다. */
    static final List<String> PROFILE_ORDER = List.of(
            "kr-bh-baseline@1", "kr-orb-30@1", "kr-vwap-reclaim@1", "kr-pdh-breakout@1", "kr-volume-surge@1",
            "kr-book-imbalance@1", "kr-pullback@1", "kr-ma-trend@1", "kr-random-control@1");
    static final List<SymbolSpec> SYMBOLS = List.of(
            new SymbolSpec("900010", new BigDecimal("8000"), 3_000, 300, 220),
            new SymbolSpec("900020", new BigDecimal("23000"), 2_000, 200, 200),
            new SymbolSpec("900030", new BigDecimal("61000"), 800, 80, 180),
            new SymbolSpec("900040", new BigDecimal("145000"), 400, 40, 160));

    private StudyMain() {
    }

    /** @param gate 정지 확인(D-063). 합성 시세를 만들기 전과 실험 중에 묻는다 */
    public static int run(String[] args, RunGate gate) {
        if (args.length != 3) {
            throw new IllegalArgumentException("인자: <저장소 루트> <커밋> <dirty true|false>");
        }
        try {
            gate.check();
            study(args, gate);
            return 0;
        } catch (ExperimentStoppedException e) {
            System.err.println("[study] 멈췄습니다(결과 파일을 쓰지 않음): " + e.getMessage());
            return e.exitCode();
        }
    }

    private static void study(String[] args, RunGate gate) {
        Path root = Path.of(args[0]);
        CodeVersion code = new CodeVersion(args[1], Boolean.parseBoolean(args[2]));
        List<StrategyProfile> profiles = loadProfiles(root.resolve("strategies"));
        SyntheticKrxRules rules = SyntheticKrxRules.INSTANCE;
        CatalogStrategyFactory factory = new CatalogStrategyFactory(rules);
        profiles.forEach(factory::validate);

        log("합성 시세 생성: " + SYMBOLS.size() + "종목 × " + DAYS + "거래일");
        SyntheticMarket.Result synthetic = new SyntheticMarket(rules).generate(SEED, START, DAYS, SYMBOLS, TREND_PER_MILLE);
        log("이벤트 " + synthetic.data().events().size() + "건, 스냅샷 " + synthetic.data().version());

        CostModel cost = CostModels.krDefault(s -> KrxBoard.KOSPI);
        // 실전과 같은 검증 체인(RiskChain: 블랙리스트·수동 보유 → 진입 규칙)으로 돈다(SIM-12).
        // 합성 종목(900xxx)은 실제 종목이 아니라 제외 종목이 있을 수 없으므로 빈 스냅샷을 쓴다.
        // 녹화 데이터 실험은 영속 블랙리스트(RSK-12)의 실험 시작 시점 스냅샷을 넣어야 하므로 이 진입점으로 돌리지 않는다.
        RiskChain risk = RiskChain.builder(BlacklistSnapshot.EMPTY).build();
        StudyConfig config = new StudyConfig(ID, SEED, Money.of(10_000_000, Money.KRW), profiles, "kr-bh-baseline",
                "kr-random-control", TRAIN_DAYS, LATENCIES, Duration.ofMillis(500), NULL_SEEDS,
                ScreeningThresholds.defaults(), 4);
        StrategyStudy study = new StrategyStudy(synthetic.data(), synthetic.tradingDays(), rules, cost, factory, risk, code,
                StudyMain::log, gate);
        StudyResult result = study.run(config);

        Path out = root.resolve("experiments").resolve(ID.value());
        write(out.resolve("results.md"), StudyReportWriter.markdown(result, cost.id(), code.commit(), code.dirty(),
                regimeCounts(synthetic.regimes(), synthetic.tradingDays())));
        write(out.resolve("runs.tsv"), StudyReportWriter.tsv(result));
        write(out.resolve("experiment.yaml"), experimentYaml(config, synthetic.data().version(), cost.id()));
        for (Screening s : result.screenings()) {
            log(s.profileId() + ": " + (s.reference() ? "참고" : s.candidate() ? "후보" : "탈락 " + s.passedCount() + "/10"));
        }
        log("결정론 확인: " + (result.deterministic() ? "같음" : "다름"));
    }

    static List<StrategyProfile> loadProfiles(Path dir) {
        List<StrategyProfile> out = new ArrayList<>();
        for (String ref : PROFILE_ORDER) {
            int at = ref.indexOf('@');
            String id = ref.substring(0, at);
            int version = Integer.parseInt(ref.substring(at + 1));
            Path file = dir.resolve(id).resolve("v" + version + ".yaml");
            try {
                StrategyProfile p = ProfileYaml.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
                if (!p.id().equals(id) || p.version() != version) {
                    throw new IllegalArgumentException(file + ": 경로와 프로필 ID·버전이 다릅니다: "
                            + p.id() + "@" + p.version());
                }
                out.add(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    static Map<String, Map<String, Integer>> regimeCounts(List<DayRegime> regimes, List<LocalDate> days) {
        LocalDate firstValidation = days.get(TRAIN_DAYS);
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        out.put("학습", new TreeMap<>());
        out.put("검증", new TreeMap<>());
        for (DayRegime r : regimes) {
            String period = r.date().isBefore(firstValidation) ? "학습" : "검증";
            out.get(period).merge(r.regime().name(), 1, Integer::sum);
        }
        return out;
    }

    static String experimentYaml(StudyConfig c, String dataVersion, String costModelId) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 실험 정의(사전 등록). StudyMain 상수에서 만든다. 결과를 본 뒤 바꾸지 않는다.\n");
        sb.append("id: ").append(c.id()).append('\n');
        sb.append("question: \"같은 합성 시세·시드·비용에서 장중 진입 로직 7개와 스윙 1개 중 선발 기준(STR-04)을 통과하는 프로필이 있는가, ")
                .append("주문 지연 0~2초에 결과가 얼마나 흔들리는가\"\n");
        sb.append("mode: REPLAY\n");
        sb.append("data:\n  kind: SYNTHETIC\n  snapshot: ").append(dataVersion).append('\n');
        sb.append("  model: ").append(SyntheticMarket.MODEL_VERSION).append('\n');
        sb.append("  start: ").append(START).append("\n  trading-days: ").append(DAYS).append('\n');
        sb.append("  trend-per-mille: ").append(TREND_PER_MILLE).append('\n');
        sb.append("  symbols: \"");
        sb.append(String.join(",", SYMBOLS.stream().map(s -> s.code() + "@" + s.startPrice().toPlainString()
                + "/depth" + s.baseDepth() + "/qty" + s.baseTradeQty() + "/vol" + s.dailyVolBp() + "bp").toList()));
        sb.append("\"\n");
        sb.append("split:\n  train-days: ").append(c.trainDays()).append("\n  validation-days: ").append(DAYS - c.trainDays())
                .append('\n');
        sb.append("seed: ").append(c.randomSeed()).append('\n');
        sb.append("seed-capital: \"").append(c.seedCapital()).append("\"\n");
        sb.append("cost-model: ").append(costModelId).append('\n');
        sb.append("execution:\n  rules: ").append(ExecutionSettings.RULES_VERSION).append("\n  base-latency-ms: ").append(c.baseLatency().toMillis()).append('\n');
        sb.append("  latencies-ms: \"").append(String.join(",", c.latencies().stream().map(d -> Long.toString(d.toMillis())).toList()))
                .append("\"\n");
        sb.append("profiles:\n");
        for (StrategyProfile p : c.profiles()) {
            sb.append("  ").append(p.id()).append(": \"v").append(p.version()).append(' ').append(p.profileHash()).append("\"\n");
        }
        sb.append("baseline: ").append(c.baselineProfileId()).append('\n');
        sb.append("control: ").append(c.controlProfileId()).append('\n');
        sb.append("null-seeds: ").append(c.nullSeeds()).append('\n');
        ScreeningThresholds t = c.thresholds();
        sb.append("screening:\n");
        sb.append("  min-profit-factor: ").append(t.minProfitFactor()).append('\n');
        sb.append("  max-drawdown: ").append(t.maxDrawdown()).append('\n');
        sb.append("  min-sharpe: ").append(t.minSharpe()).append('\n');
        sb.append("  min-round-trips: ").append(t.minRoundTrips()).append('\n');
        sb.append("  min-oos-ratio: ").append(t.minOosRatio()).append('\n');
        sb.append("  min-perturbed-ratio: ").append(t.minPerturbedRatio()).append('\n');
        sb.append("  min-perturbed-profit-factor: ").append(t.minPerturbedProfitFactor()).append('\n');
        sb.append("  stress-latency-ms: ").append(t.stressLatency().toMillis()).append('\n');
        sb.append("  min-stress-profit-factor: ").append(t.minStressProfitFactor()).append('\n');
        sb.append("  null-percentile: ").append(t.nullPercentile()).append('\n');
        return sb.toString();
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log("작성: " + file);
    }

    private static void log(String message) {
        System.out.println("[study] " + message);
    }
}
