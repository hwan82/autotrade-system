package com.autotrade.sim.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.MarketDataSet;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.synthetic.SyntheticKrxRules;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradeSignal;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.catalog.CatalogStrategyFactory;
import com.autotrade.strategy.profile.ProfileYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * 일봉 데이터셋의 일 색인 자르기 경로(ADR-009 2.3, {@code MarketDataSet.tradingDays()})가 이벤트를 훑는 선형 경로와 같은 결과를
 * 내는지, 그리고 일 색인 경로에서도 예열 계약(전략만 봄, 체결·평가는 시작일부터, 구간 끝 뒤 시세 없음, 부족하면 거부)이 지켜지는지
 * (PR #79 architect 검토 M2). 시세: 2026-01-05부터 평일 60일, 세 종목, 50원 배수 삼각파.
 */
class DailySlicePathTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Symbol[] SYMBOLS = {Symbol.krx("000010"), Symbol.krx("000020"), Symbol.krx("000030")};
    private static final List<LocalDate> DAYS = days();
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final SyntheticKrxRules RULES = SyntheticKrxRules.INSTANCE;

    private static List<LocalDate> days() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2026, 1, 5); out.size() < 60; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }

    private static DailyBarMarketDataSet indexed() {
        DailyBarMarketDataSetTest.MemorySource src = new DailyBarMarketDataSetTest.MemorySource();
        for (int i = 0; i < DAYS.size(); i++) {
            for (int s = 0; s < SYMBOLS.length; s++) {
                int period = 10 + 4 * s;
                int p = (i + 3 * s) % period;
                int t = p < period / 2 ? p : period - p;
                long close = 11_000 + 50L * t;
                src.bar(SYMBOLS[s], DAYS.get(i), Long.toString(close), Long.toString(close + 50),
                        Long.toString(close - 50), Long.toString(close), 10_000, "1");
            }
        }
        return DailyBarMarketDataSet.of(src, "slice-test", Market.KRX, SEOUL, DAYS.get(0), DAYS.get(DAYS.size() - 1));
    }

    /** 거래일 색인을 숨겨 실행기가 이벤트를 훑는 선형 경로로 자르게 한다. 이벤트·버전·시각 성격은 원본 그대로. */
    private record Linear(MarketDataSet d) implements MarketDataSet {
        @Override
        public String version() {
            return d.version();
        }

        @Override
        public Market market() {
            return d.market();
        }

        @Override
        public List<MarketEvent> events() {
            return d.events();
        }

        @Override
        public boolean exchangeTimed() {
            return d.exchangeTimed();
        }
    }

    private static StrategyProfile repoProfile(String id) throws IOException {
        Path f = Path.of("../strategies").resolve(id).resolve("v1.yaml");
        return ProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
    }

    private static SimulationReport run(MarketDataSet data, StrategyFactory factory, List<StrategyProfile> profiles,
            LocalDate from, LocalDate to, int warmup) {
        var runner = new SimulationExperimentRunner(data, RULES, COST, factory,
                RiskChain.builder(BlacklistSnapshot.of(List.of())).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, null, null, RunGate.NONE, warmup, true);
        ExperimentSpec spec = ExperimentSpec.ofProfiles(new ExperimentId("EXP-TEST-SLICE"), from, to, 7L,
                Money.of(10_000_000, Money.KRW), COST.id(), data.version(), ClockMode.REPLAY, profiles);
        return runner.runDetailed(spec);
    }

    @Test
    void 일_색인_경로와_선형_경로의_보고서가_바이트까지_같다() throws IOException {
        DailyBarMarketDataSet data = indexed();
        assertThat(data.tradingDays()).as("일 색인 경로를 실제로 탄다").isEqualTo(DAYS);
        assertThat(new Linear(data).tradingDays()).as("래퍼는 선형 경로").isEmpty();
        CatalogStrategyFactory factory = new CatalogStrategyFactory(RULES);
        List<StrategyProfile> profiles = List.of(repoProfile("kr-ma-trend"), repoProfile("kr-donchian-swing"),
                repoProfile("kr-random-daily-swing"));
        LocalDate from = DAYS.get(30);
        LocalDate to = DAYS.get(49);
        for (int warmup : new int[] {0, 21}) {
            SimulationReport byIndex = run(data, factory, profiles, from, to, warmup);
            SimulationReport linear = run(new Linear(data), factory, profiles, from, to, warmup);
            assertThat(byIndex.runs()).as("예열 " + warmup + ": 체결이 있어야 비교가 의미 있다")
                    .anySatisfy(r -> assertThat(r.fills()).isNotEmpty());
            assertThat(ReportWriter.render(byIndex)).as("예열 " + warmup).isEqualTo(ReportWriter.render(linear));
        }
    }

    /** 이벤트 날짜를 기록하고 보유가 없으면 매 이벤트 1주 시장가 매수를 시도하는 전략. */
    private static final class Recorder {
        final List<LocalDate> seen = new CopyOnWriteArrayList<>();

        StrategyFactory factory() {
            return (p, seed) -> (Strategy) (event, ctx) -> {
                seen.add(event.exchangeTime().atZone(SEOUL).toLocalDate());
                Symbol s = SYMBOLS[0];
                return event.symbol().equals(s) && ctx.account().quantityOf(s) == 0 && !ctx.hasOpenOrder(s)
                        ? List.of(TradeSignal.marketBuy(s, 1)) : List.of();
            };
        }
    }

    private static final StrategyProfile PROBE = new StrategyProfile("probe-buy", 1, Market.KRX, TradingStyle.SWING,
            new TreeMap<>());

    @Test
    void 일_색인_경로에서도_예열은_전략만_보고_체결과_평가는_시작일부터이며_구간_끝_뒤_시세는_없다() {
        Recorder rec = new Recorder();
        LocalDate from = DAYS.get(10);
        LocalDate to = DAYS.get(14);
        SimulationReport r = run(indexed(), rec.factory(), List.of(PROBE), from, to, 3);

        assertThat(rec.seen).first().isEqualTo(DAYS.get(7));
        assertThat(rec.seen).last().isEqualTo(to);
        assertThat(rec.seen.stream().distinct().toList()).containsExactlyElementsOf(DAYS.subList(7, 15));
        RunReport run = r.runs().get(0);
        assertThat(run.equityCurve()).extracting(p -> p.date()).containsExactlyElementsOf(DAYS.subList(10, 15));
        assertThat(run.fills()).isNotEmpty().allSatisfy(f -> assertThat(f.fill().exchangeDate()).isAfterOrEqualTo(from));
        // 예열 때 낸 매수 신호는 버려져 시작일에 새로 신호가 나고 그날 다음 칸에 한 번 체결된다
        assertThat(run.fills().get(0).fill().exchangeDate()).isEqualTo(from);
        // 매수 한 번과 구간 끝(마지막 거래일) 정리 매도 한 번
        assertThat(run.fills()).hasSize(2);
        assertThat(run.fills().get(1).fill().exchangeDate()).isEqualTo(to);
        assertThat(run.windowEndLots()).containsExactly(run.fills().get(0).lotId());
    }

    @Test
    void 일_색인_경로에서도_앞선_거래일이_모자라면_시작하지_않는다() {
        assertThatThrownBy(() -> run(indexed(), new Recorder().factory(), List.of(PROBE), DAYS.get(2), DAYS.get(5), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("예열 거래일이 모자랍니다");
    }
}
