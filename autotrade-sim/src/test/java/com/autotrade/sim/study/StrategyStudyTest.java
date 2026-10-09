package com.autotrade.sim.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.synthetic.SymbolSpec;
import com.autotrade.sim.synthetic.SyntheticKrxRules;
import com.autotrade.sim.synthetic.SyntheticMarket;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 비교 실험 실행기: 결정론, 구간 분리, 변형·대조군 구성, 선발 기준 계산을 작은 합성 데이터로 확인한다. */
class StrategyStudyTest {

    private static final SyntheticKrxRules RULES = SyntheticKrxRules.INSTANCE;
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);

    @Test
    void 작은_실험을_끝까지_돌리면_결정론이고_표가_재현된다() {
        StudyResult a = run();
        StudyResult b = run();
        String costId = COST.id();
        assertThat(a.deterministic()).isTrue();
        assertThat(StudyReportWriter.markdown(a, costId, "abc", false, Map.of()))
                .isEqualTo(StudyReportWriter.markdown(b, costId, "abc", false, Map.of()));
        assertThat(StudyReportWriter.tsv(a)).isEqualTo(StudyReportWriter.tsv(b));

        // 학습 3일 / 검증 2일 × 지연 2개, 변형 1쌍, 대조군 3번
        assertThat(a.grid()).hasSize(4);
        ExperimentOutcome val = a.outcome("VALIDATION", Duration.ofMillis(500));
        assertThat(val.tradingDays()).isEqualTo(2);
        assertThat(val.from()).isEqualTo(a.tradingDays().get(3));
        assertThat(val.runs().values()).allMatch(r -> r.status() == RunStatus.COMPLETED);
        assertThat(a.perturbation().runs()).containsOnlyKeys("rnd-k80", "rnd-k120");
        assertThat(a.perturbation().run("rnd-k80").profileHash()).isNotEqualTo(a.perturbation().run("rnd-k120").profileHash());
        assertThat(a.nullRuns()).hasSize(3).extracting(ExperimentOutcome::randomSeed).containsExactly(2L, 3L, 4L);
        assertThat(a.screenings()).extracting(Screening::profileId).containsExactly("bh", "rnd");
        assertThat(a.screenings().get(0).reference()).isTrue();
        assertThat(a.screenings().get(1).checks()).hasSize(10);
    }

    @Test
    void 변형은_소수점_없는_값을_정수로_소수점_있는_값을_그대로_곱한다() {
        StrategyProfile p = new StrategyProfile("x", 1, Market.KRX, TradingStyle.INTRADAY,
                new TreeMap<>(Map.of("a", "8", "b", "1.0", "c", "3")));
        assertThat(StrategyStudy.Perturbation.scale(p, "a", new BigDecimal("0.8"), "k80").parameters().get("a")).isEqualTo("6");
        assertThat(StrategyStudy.Perturbation.scale(p, "a", new BigDecimal("1.2"), "k120").parameters().get("a")).isEqualTo("10");
        assertThat(StrategyStudy.Perturbation.scale(p, "b", new BigDecimal("0.8"), "k80").parameters().get("b")).isEqualTo("0.8");
        assertThat(StrategyStudy.Perturbation.scale(p, "c", new BigDecimal("1.2"), "k120").parameters().get("c"))
                .as("3 × 1.2 = 3.6 → 4").isEqualTo("4");
        StrategyProfile one = new StrategyProfile("y", 1, Market.KRX, TradingStyle.INTRADAY, new TreeMap<>(Map.of("n", "2")));
        assertThat(StrategyStudy.Perturbation.scale(one, "n", new BigDecimal("1.2"), "k120").parameters().get("n"))
                .as("2 × 1.2 = 2.4 → 2와 같으면 1 올린다").isEqualTo("3");
        assertThat(StrategyStudy.Perturbation.scale(p, "a", new BigDecimal("0.8"), "k80").id()).isEqualTo("x-k80");
    }

    @Test
    void 분위수는_가장_가까운_순위() {
        List<BigDecimal> xs = List.of(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"), new BigDecimal("4"));
        assertThat(ScreeningCriteria.quantile(xs, new BigDecimal("0.95"))).isEqualByComparingTo("4");
        assertThat(ScreeningCriteria.quantile(xs, new BigDecimal("0.5"))).isEqualByComparingTo("2");
        assertThat(ScreeningCriteria.quantile(List.of(), new BigDecimal("0.5"))).isNull();
    }

    @Test
    void 저장소_프로필로도_실험이_시작된다() {
        List<StrategyProfile> profiles = StudyMain.loadProfiles(Path.of("../strategies"));
        assertThat(profiles).extracting(p -> p.id() + "@" + p.version()).containsExactlyElementsOf(StudyMain.PROFILE_ORDER);
        CatalogStrategyFactory factory = new CatalogStrategyFactory(RULES);
        profiles.forEach(factory::validate);
    }

    private static StudyResult run() {
        var synth = new SyntheticMarket(RULES).generate(1L, LocalDate.of(2026, 3, 2), 5,
                List.of(new SymbolSpec("900010", new BigDecimal("8000"), 1000, 100, 220),
                        new SymbolSpec("900020", new BigDecimal("23000"), 800, 80, 200)), 350);
        List<StrategyProfile> profiles = List.of(bh(), rnd());
        StudyConfig config = new StudyConfig(new ExperimentId("EXP-TEST"), 1L, Money.of(10_000_000, Money.KRW),
                profiles, "bh", "rnd", 3, List.of(Duration.ofMillis(500), Duration.ofSeconds(2)), Duration.ofMillis(500),
                3, ScreeningThresholds.defaults(), 2);
        return new StrategyStudy(synth.data(), synth.tradingDays(), RULES, COST, new CatalogStrategyFactory(RULES),
                RiskChain.builder(BlacklistSnapshot.EMPTY).build(), new CodeVersion("abc", false), m -> { }).run(config);
    }

    private static StrategyProfile bh() {
        return new StrategyProfile("bh", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>(Map.of(
                "hypothesis", "기준선", "logic", "buy-and-hold", "sizing.slots", "2", "sizing.cash-buffer-pct", "1")));
    }

    private static StrategyProfile rnd() {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "대조군");
        p.put("logic", "random");
        p.put("signal.per-mille", "5");
        p.put("entry.window.start", "09:30");
        p.put("entry.window.end", "14:30");
        p.put("entry.expiry-seconds", "600");
        p.put("sizing.risk-per-trade-pct", "0.5");
        p.put("sizing.max-position-pct", "20");
        p.put("sizing.max-positions", "2");
        p.put("sizing.cash-buffer-pct", "5");
        p.put("limits.max-entries-per-day", "6");
        p.put("limits.reentry-cooldown-minutes", "30");
        p.put("limits.daily-loss-limit-pct", "2");
        p.put("limits.max-consecutive-stops", "3");
        p.put("exit.stop-loss-pct", "1.5");
        p.put("exit.time-exit-minutes", "60");
        p.put("exit.eod", "FLATTEN");
        p.put("exit.eod-time", "15:10");
        p.put("study.key-param", "signal.per-mille");
        return new StrategyProfile("rnd", 1, Market.KRX, TradingStyle.INTRADAY, p);
    }
}
