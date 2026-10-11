package com.autotrade.sim.study;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.risk.RiskChain;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.MarketDataSet;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 다중 전략 비교 실험 실행(4단계, autotrade-strategy-lab 스킬 2~5장). 실험 설계({@link StudyConfig})를 받아 아래를 돌린다.
 * <ol>
 *   <li>학습·검증 구간 × 주문 지연 목록마다 실험 하나(모든 프로필이 같은 기간·시드·비용·체결 모델)</li>
 *   <li>결정론 확인: 검증 구간 기본 지연 실험을 병렬 수 1로 다시 돌려 정규 텍스트가 바이트 단위로 같은지</li>
 *   <li>파라미터 민감도: 프로필의 {@code study.key-param} 값을 ×0.8, ×1.2로 바꾼 변형을 학습 구간에서
 *       (검증 구간을 보고 고치지 않도록 학습 구간에서만)</li>
 *   <li>대조군 분포: 무작위 진입 프로필을 시드만 바꿔 검증 구간에서 여러 번</li>
 *   <li>선발 기준 판정({@link ScreeningCriteria})</li>
 * </ol>
 * 실행기({@link SimulationExperimentRunner})를 그대로 쓰므로 체결·비용·지표 규칙은 ADR-004와 같다.
 */
public final class StrategyStudy {

    private final MarketDataSet data;
    private final List<LocalDate> tradingDays;
    private final MarketRules rules;
    private final CostModel cost;
    private final StrategyFactory strategies;
    private final RiskChain risk;
    private final CodeVersion code;
    private final Consumer<String> progress;

    /**
     * @param tradingDays 데이터의 거래일(오름차순). 학습·검증 구간을 나눌 때 쓴다
     * @param risk 모의 주문 검증 체인. 실전과 같은 {@link RiskChain}만 받는다(SIM-12)
     * @param progress 진행 표시(로그용, 결과에 영향 없음)
     */
    public StrategyStudy(
            MarketDataSet data, List<LocalDate> tradingDays, MarketRules rules, CostModel cost,
            StrategyFactory strategies, RiskChain risk, CodeVersion code, Consumer<String> progress) {
        this.data = Objects.requireNonNull(data, "data");
        this.tradingDays = List.copyOf(tradingDays);
        this.rules = Objects.requireNonNull(rules, "rules");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.strategies = Objects.requireNonNull(strategies, "strategies");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.code = Objects.requireNonNull(code, "code");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    public StudyResult run(StudyConfig config) {
        if (config.trainDays() >= tradingDays.size()) {
            throw new IllegalArgumentException("검증 구간이 없습니다: 학습 " + config.trainDays() + "일 / 전체 " + tradingDays.size());
        }
        LocalDate trainFrom = tradingDays.get(0);
        LocalDate trainTo = tradingDays.get(config.trainDays() - 1);
        LocalDate valFrom = tradingDays.get(config.trainDays());
        LocalDate valTo = tradingDays.get(tradingDays.size() - 1);
        int valDays = tradingDays.size() - config.trainDays();

        List<ExperimentOutcome> grid = new ArrayList<>();
        for (Duration latency : config.latencies()) {
            grid.add(experiment(config, "TRAIN", trainFrom, trainTo, config.trainDays(), latency, config.randomSeed(),
                    config.profiles(), config.parallelism()));
            grid.add(experiment(config, "VALIDATION", valFrom, valTo, valDays, latency, config.randomSeed(),
                    config.profiles(), config.parallelism()));
        }

        progress.accept("결정론 확인: 검증 구간 기본 지연 실험을 병렬 수 1로 다시 실행");
        boolean deterministic = sameCanonical(config, valFrom, valTo);

        List<StrategyProfile> variants = new ArrayList<>();
        Map<String, PerturbedPair> perturbations = new LinkedHashMap<>();
        for (StrategyProfile p : config.profiles()) {
            String key = p.parameters().get("study.key-param");
            if (key == null) {
                continue;
            }
            StrategyProfile low = Perturbation.scale(p, key, new BigDecimal("0.8"), "k80");
            StrategyProfile high = Perturbation.scale(p, key, new BigDecimal("1.2"), "k120");
            if (!valid(low) || !valid(high)) {
                // 변형이 프로필 범위를 벗어나면 C7은 판단 불가로 둔다(불안정과 섞지 않음)
                progress.accept(p.id() + ": ±20% 변형이 파라미터 범위 밖이라 C7 판단 불가");
                continue;
            }
            variants.add(low);
            variants.add(high);
            perturbations.put(p.id(), new PerturbedPair(key, low.parameters().get(key), high.parameters().get(key),
                    low.id(), high.id()));
        }
        ExperimentOutcome perturbed = variants.isEmpty() ? null
                : experiment(config, "PERTURB", trainFrom, trainTo, config.trainDays(), config.baseLatency(),
                        config.randomSeed(), variants, config.parallelism());

        StrategyProfile control = config.profiles().stream()
                .filter(p -> p.id().equals(config.controlProfileId())).findFirst().orElseThrow();
        List<ExperimentOutcome> nullRuns = new ArrayList<>();
        for (int i = 1; i <= config.nullSeeds(); i++) {
            nullRuns.add(experiment(config, "NULL" + i, valFrom, valTo, valDays, config.baseLatency(),
                    config.randomSeed() + i, List.of(control), 1));
        }

        StudyResult partial = new StudyResult(config, data.version(), tradingDays, grid, perturbed, perturbations,
                nullRuns, deterministic, List.of());
        List<Screening> screenings = ScreeningCriteria.evaluate(partial);
        return new StudyResult(config, data.version(), tradingDays, grid, perturbed, perturbations, nullRuns,
                deterministic, screenings);
    }

    private boolean valid(StrategyProfile p) {
        try {
            strategies.create(p, 0L);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 변형 한 쌍: 원래 프로필 ID → 변형 키와 값. */
    public record PerturbedPair(String key, String lowValue, String highValue, String lowId, String highId) {
    }

    private ExperimentOutcome experiment(
            StudyConfig config, String period, LocalDate from, LocalDate to, int days, Duration latency, long seed,
            List<StrategyProfile> profiles, int parallelism) {
        String id = config.id().value() + "-" + period + "-L" + latency.toMillis();
        progress.accept("실험 " + id + " (" + from + " ~ " + to + ", 프로필 " + profiles.size() + "개)");
        SimulationReport report = runner(latency, parallelism).runDetailed(spec(config, id, from, to, seed, profiles));
        Map<String, RunSummary> runs = new LinkedHashMap<>();
        for (RunReport r : report.runs()) {
            runs.put(r.profile().id(), summarize(r));
        }
        return new ExperimentOutcome(id, period, from, to, days, seed, latency, report.result().executionModelId(),
                report.result().costTableHash(), runs);
    }

    private boolean sameCanonical(StudyConfig config, LocalDate from, LocalDate to) {
        String id = config.id().value() + "-DETERMINISM";
        ExperimentSpec spec = spec(config, id, from, to, config.randomSeed(), config.profiles());
        String a = ReportWriter.render(runner(config.baseLatency(), config.parallelism()).runDetailed(spec));
        String b = ReportWriter.render(runner(config.baseLatency(), 1).runDetailed(spec));
        return a.equals(b);
    }

    private ExperimentSpec spec(
            StudyConfig config, String id, LocalDate from, LocalDate to, long seed, List<StrategyProfile> profiles) {
        return ExperimentSpec.ofProfiles(new ExperimentId(id), from, to, seed, config.seedCapital(), cost.id(), data.version(),
                ClockMode.REPLAY, profiles);
    }

    private SimulationExperimentRunner runner(Duration latency, int parallelism) {
        return new SimulationExperimentRunner(data, rules, cost, strategies, risk, new ExecutionSettings(latency), code,
                parallelism, null);
    }

    private static RunSummary summarize(RunReport r) {
        int stops = 0;
        for (AppliedFill f : r.fills()) {
            if (f.fill().side() == Side.SELL && f.fill().intent() == OrderIntent.STOP_LOSS) {
                stops++;
            }
        }
        TreeMap<String, Integer> rejections = new TreeMap<>();
        for (OrderRejection j : r.rejections()) {
            rejections.merge(j.stage() + ":" + j.code(), 1, Integer::sum);
        }
        return new RunSummary(r.profile().id(), r.summary().profileHash(), r.summary().status(),
                r.summary().failureReason(), r.summary().metrics(), r.equityCurve(), stops, rejections);
    }

    /** 핵심 파라미터 배율 변형. */
    static final class Perturbation {

        private Perturbation() {
        }

        /**
         * {@code key} 값을 {@code factor}배 한 변형. 소수점 없이 쓴 값(개수·분·일)은 정수로 반올림하고, 반올림 결과가 원래와
         * 같으면 1만큼 움직인다. 소수점이 있는 값은 그대로 곱한다(그래서 비율 파라미터는 {@code 3.0}처럼 소수점을 붙여 쓴다).
         * 새 ID는 {@code <원래 ID>-<suffix>}이고 버전은 그대로다(다른 내용이 같은 ID@버전을 갖지 않게).
         */
        static StrategyProfile scale(StrategyProfile p, String key, BigDecimal factor, String suffix) {
            String raw = p.parameters().get(key);
            if (raw == null) {
                throw new IllegalArgumentException(p.id() + ": study.key-param이 가리키는 키가 없습니다: " + key);
            }
            BigDecimal original = new BigDecimal(raw);
            BigDecimal scaled = original.multiply(factor);
            String value;
            if (original.scale() <= 0) {
                BigDecimal rounded = scaled.setScale(0, RoundingMode.HALF_UP);
                if (rounded.compareTo(original) == 0) {
                    rounded = factor.compareTo(BigDecimal.ONE) < 0 ? original.subtract(BigDecimal.ONE)
                            : original.add(BigDecimal.ONE);
                }
                value = rounded.toPlainString();
            } else {
                value = scaled.stripTrailingZeros().toPlainString();
            }
            TreeMap<String, String> params = new TreeMap<>(p.parameters());
            params.put(key, value);
            return new StrategyProfile(p.id() + "-" + suffix, p.version(), p.market(), p.style(), params);
        }
    }
}
