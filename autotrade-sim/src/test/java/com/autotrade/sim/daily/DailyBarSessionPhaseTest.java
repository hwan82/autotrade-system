package com.autotrade.sim.daily;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradeSignal;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 일봉 경로는 칸 시각이 합성이라 실제 KRX 규칙으로 돌려도 매매 단계를 보지 않는다(D-069, architect PR #74 F2).
 * 캘린더 수록 범위(2026~2027) 밖 과거와 10:00 개장일 09:00 칸에서도 체결된다.
 */
class DailyBarSessionPhaseTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Symbol A = Symbol.krx("000020");
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final KrxMarketRules RULES =
            new KrxMarketRules(ExchangeCalendar.bundled(Market.KRX), s -> InstrumentKind.STOCK);

    @Test
    void 캘린더_수록_범위_밖_날짜의_일봉_실험도_Run이_끝까지_돈다() {
        RunReport run = run(LocalDate.of(2025, 6, 2), LocalDate.of(2025, 6, 4));
        assertThat(run.summary().status()).isEqualTo(RunStatus.COMPLETED);
        // 첫 칸(09:00 시가) 신호 → 다음 칸(09:10 시가) 체결
        assertThat(run.fills()).singleElement().satisfies(f -> assertThat(f.fill().fillTime().atZone(SEOUL)
                .toLocalTime()).isEqualTo(LocalTime.of(9, 10)));
    }

    @Test
    void 개장이_늦은_날도_시가_칸에서_체결된다() {
        // 2026-01-02는 번들 캘린더에서 10:00 개장. 칸 시각 09:10은 실제로는 장 전이지만 일봉의 시가 칸이다
        RunReport run = run(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 5));
        assertThat(run.summary().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.fills()).singleElement().satisfies(f -> assertThat(f.fill().fillTime().atZone(SEOUL))
                .satisfies(t -> {
                    assertThat(t.toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 2));
                    assertThat(t.toLocalTime()).isEqualTo(LocalTime.of(9, 10));
                }));
    }

    private static RunReport run(LocalDate d1, LocalDate d2) {
        DailyBarMarketDataSetTest.MemorySource src = new DailyBarMarketDataSetTest.MemorySource()
                .bar(A, d1, "10000", "10200", "9900", "10100", 600, "1")
                .bar(A, d2, "10100", "10300", "10000", "10200", 600, "1");
        DailyBarMarketDataSet data = DailyBarMarketDataSet.of(src, "snap", Market.KRX, SEOUL, d1, d2);
        assertThat(data.exchangeTimed()).isFalse();
        StrategyProfile profile = new StrategyProfile("first-buy", 1, Market.KRX, TradingStyle.INTRADAY, new TreeMap<>());
        SimulationReport report = new SimulationExperimentRunner(data, RULES, COST, (p, seed) -> {
            boolean[] bought = {false};
            return (e, ctx) -> {
                if (!bought[0] && e instanceof TradeTick) {
                    bought[0] = true;
                    return List.of(TradeSignal.marketBuy(A, 10));
                }
                return List.of();
            };
        }, RiskChain.builder(BlacklistSnapshot.EMPTY).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, null)
                .runDetailed(ExperimentSpec.ofProfiles(new ExperimentId("EXP-20261013-DAY"), d1, d2, 7,
                        Money.of(10_000_000, Money.KRW), COST.id(), data.version(), ClockMode.REPLAY, List.of(profile)));
        assertThat(report.runs()).hasSize(1);
        return report.runs().get(0);
    }
}
