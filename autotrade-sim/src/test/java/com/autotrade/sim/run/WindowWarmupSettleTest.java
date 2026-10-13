package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.risk.BlacklistSnapshot;
import com.autotrade.risk.RiskChain;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.testing.TestKrxRules;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradeSignal;
import com.autotrade.strategy.TradingStyle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * 워크포워드 실행기 계약(4단계 계획 3장): 예열 시세는 전략에만 가고(체결·평가·미래 정보 없음), 구간 끝 정리는 손 계산 값으로
 * 왕복에 들어간다. 시세: 2026-03-02(월)~03-06(금), 매일 10:00·10:30 체결 2개(가격 10,000 고정).
 */
class WindowWarmupSettleTest {

    private static final Symbol S = Symbol.krx("005930");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<LocalDate> DAYS = List.of(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 6));
    private static final CostModel COST = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final StrategyProfile PROFILE = new StrategyProfile("probe-buy", 1, Market.KRX, TradingStyle.SWING,
            new TreeMap<>());

    private static MarketDataSet data() {
        List<MarketEvent> events = new ArrayList<>();
        long seq = 1;
        for (LocalDate d : DAYS) {
            for (int minute : new int[] {0, 30}) {
                var t = LocalDateTime.of(d, java.time.LocalTime.of(10, minute)).atZone(SEOUL).toInstant();
                events.add(new TradeTick(S, seq++, t, t, new BigDecimal("10000"), 100));
            }
        }
        return new InMemoryMarketDataSet("WARMUP-TEST", Market.KRX, events);
    }

    /** 이벤트 날짜를 기록하고 매 이벤트마다 1주 시장가 매수를 시도하는 전략(보유가 없을 때). */
    private static final class Recorder {
        final List<LocalDate> seen = new CopyOnWriteArrayList<>();

        Strategy strategy() {
            return (event, ctx) -> {
                seen.add(event.exchangeTime().atZone(SEOUL).toLocalDate());
                return ctx.account().quantityOf(S) == 0 && !ctx.hasOpenOrder(S) ? List.of(TradeSignal.marketBuy(S, 1))
                        : List.of();
            };
        }
    }

    private static SimulationReport run(Recorder rec, LocalDate from, LocalDate to, int warmup, boolean settle) {
        MarketDataSet data = data();
        var runner = new SimulationExperimentRunner(data, TestKrxRules.INSTANCE, COST, (p, seed) -> rec.strategy(),
                RiskChain.builder(BlacklistSnapshot.of(List.of())).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, null, null, RunGate.NONE, warmup, settle);
        ExperimentSpec spec = ExperimentSpec.ofProfiles(new ExperimentId("EXP-TEST-WARM"), from, to, 1L,
                Money.of(10_000_000, Money.KRW), COST.id(), data.version(), ClockMode.REPLAY, List.of(PROFILE));
        return runner.runDetailed(spec);
    }

    @Test
    void 예열_시세는_전략에만_가고_그_기간에는_체결도_자산_곡선도_없다() {
        Recorder rec = new Recorder();
        SimulationReport r = run(rec, DAYS.get(2), DAYS.get(4), 2, false);

        assertThat(rec.seen).containsExactly(DAYS.get(0), DAYS.get(0), DAYS.get(1), DAYS.get(1), DAYS.get(2), DAYS.get(2),
                DAYS.get(3), DAYS.get(3), DAYS.get(4), DAYS.get(4));
        RunReport run = r.runs().get(0);
        assertThat(run.equityCurve()).extracting(p -> p.date()).containsExactly(DAYS.get(2), DAYS.get(3), DAYS.get(4));
        assertThat(run.fills()).isNotEmpty().allSatisfy(f -> assertThat(f.fill().exchangeDate()).isAfterOrEqualTo(DAYS.get(2)));
        // 예열 때 낸 매수 신호는 버려져 시작일 첫 체결(10:00)에서 새로 신호가 나고 10:30에 체결된다
        assertThat(run.fills().get(0).fill().fillTime())
                .isEqualTo(LocalDateTime.of(DAYS.get(2), java.time.LocalTime.of(10, 30)).atZone(SEOUL).toInstant());
        assertThat(run.fills()).hasSize(1);
    }

    @Test
    void 예열_뒤_구간_끝_이후_시세는_전략에_가지_않는다() {
        Recorder rec = new Recorder();
        run(rec, DAYS.get(2), DAYS.get(3), 1, false);
        assertThat(rec.seen).doesNotContain(DAYS.get(4)).contains(DAYS.get(1), DAYS.get(2), DAYS.get(3))
                .doesNotContain(DAYS.get(0));
    }

    @Test
    void 예열_일수가_0이면_예열_없는_실행과_같다() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        String withZero = ReportWriter.render(run(a, DAYS.get(1), DAYS.get(4), 0, false));
        String plain = ReportWriter.render(runWithoutWarmupConstructor(b));
        assertThat(withZero).isEqualTo(plain);
        assertThat(a.seen).isEqualTo(b.seen).doesNotContain(DAYS.get(0));
    }

    private static SimulationReport runWithoutWarmupConstructor(Recorder rec) {
        MarketDataSet data = data();
        var runner = new SimulationExperimentRunner(data, TestKrxRules.INSTANCE, COST, (p, seed) -> rec.strategy(),
                RiskChain.builder(BlacklistSnapshot.of(List.of())).build(), ExecutionSettings.defaults(),
                new CodeVersion("test", false), 1, null);
        ExperimentSpec spec = ExperimentSpec.ofProfiles(new ExperimentId("EXP-TEST-WARM"), DAYS.get(1), DAYS.get(4), 1L,
                Money.of(10_000_000, Money.KRW), COST.id(), data.version(), ClockMode.REPLAY, List.of(PROFILE));
        return runner.runDetailed(spec);
    }

    @Test
    void 앞선_거래일이_모자라면_시작하지_않는다() {
        assertThatThrownBy(() -> run(new Recorder(), DAYS.get(2), DAYS.get(4), 3, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("예열 거래일이 모자랍니다");
    }

    @Test
    void 구간_끝_정리는_종가_슬리피지와_비용을_뺀_왕복으로_들어간다() {
        SimulationReport r = run(new Recorder(), DAYS.get(2), DAYS.get(4), 0, true);
        RunReport run = r.runs().get(0);
        // 매수: 10,000 x 1.0015 = 10,015 → 호가 단위 10원 올림 10,020, 수수료 floor(1.503)=1. 원가 10,021
        // 매도(구간 끝 정리): 10,000 x 0.9985 = 9,985 → 내림 9,980. 수수료 floor(1.497)=1, 거래세 floor(4.99)=4, 농특세 floor(14.97)=14
        // 실현 = 9,980 − 19 − 10,021 = −60
        assertThat(run.roundTrips()).singleElement().satisfies(t -> {
            assertThat(t.pnl()).isEqualTo(Money.of(-60, Money.KRW));
            assertThat(t.exitDate()).isEqualTo(DAYS.get(4));
            assertThat(run.windowEndLots()).contains(t.lotId());
        });
        assertThat(run.equityCurve().get(run.equityCurve().size() - 1).equity()).isEqualTo(Money.of(9_999_940, Money.KRW));
        assertThat(run.summary().metrics().roundTripCount()).isEqualTo(1);
        assertThat(ReportWriter.render(r)).contains(" window-end");
        // 정리를 끄면 왕복이 없고 평가손익만 남는다(10,000 x 1주 평가, 매수 비용 21)
        RunReport open = run(new Recorder(), DAYS.get(2), DAYS.get(4), 0, false).runs().get(0);
        assertThat(open.roundTrips()).isEmpty();
        assertThat(open.windowEndLots()).isEmpty();
        assertThat(open.equityCurve().get(open.equityCurve().size() - 1).equity()).isEqualTo(Money.of(9_999_979, Money.KRW));
    }
}
