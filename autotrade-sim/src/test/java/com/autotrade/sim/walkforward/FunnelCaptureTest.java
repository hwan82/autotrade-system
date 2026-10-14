package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.MetricsCalculator;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.RunSeeds;
import com.autotrade.selection.profile.Combination;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import com.autotrade.strategy.catalog.EntryFunnel;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Run과 측정(진입 깔때기·청산 사유)을 잇는 {@link WalkForwardStudy.FunnelCapture}의 직접 테스트(PR #98 architect 권고 m1).
 * 시드 파생이 바뀌어 키가 어긋나면 완료된 Run에서는 예외로 멈추고, 전략을 만들기 전에 실패한 Run만 0으로 넘어간다.
 */
class FunnelCaptureTest {

    private static final long SPEC_SEED = 7L;
    private static final LocalDate D1 = LocalDate.of(2024, 1, 2);
    private static final LocalDate D2 = LocalDate.of(2024, 1, 3);
    private static final Money SEED = Money.of(1_000_000, Money.KRW);
    private static final Symbol S = Symbol.krx("005930");
    private static final StrategyProfile PROFILE = new StrategyProfile("probe-test", 1, Market.KRX, TradingStyle.SWING,
            new TreeMap<>());
    /** 시세를 받아도 아무것도 하지 않는 전략(ManagedStrategy 밖이라 측정이 없다). */
    private static final Strategy IDLE = (event, ctx) -> List.of();

    private static RunReport completed(List<RoundTrip> trips, Set<String> windowEndLots) {
        List<EquityPoint> eq = List.of(point(D1, 1_000_000), point(D2, 1_010_000));
        var m = MetricsCalculator.compute(SEED, D1, D2, eq, List.of(), trips, null);
        RunResult r = new RunResult("R-1", new AccountId("A-1"), PROFILE.profileHash(), seed(), RunStatus.COMPLETED,
                Money.of(1_010_000, Money.KRW), "", m);
        return new RunReport(r, Combination.of(PROFILE), List.of(), eq, trips, List.of(), List.of(), windowEndLots);
    }

    private static RunReport failed() {
        RunResult r = new RunResult("R-2", new AccountId("A-2"), PROFILE.profileHash(), seed(), RunStatus.FAILED, null,
                "전략 생성 실패", null);
        return new RunReport(r, Combination.of(PROFILE), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of());
    }

    private static long seed() {
        return RunSeeds.derive(SPEC_SEED, Combination.of(PROFILE));
    }

    private static EquityPoint point(LocalDate d, long v) {
        return new EquityPoint(d, d.atTime(15, 30).toInstant(ZoneOffset.ofHours(9)), Money.of(v, Money.KRW),
                Money.of(v, Money.KRW));
    }

    private static RoundTrip trip(String lot) {
        return new RoundTrip(lot, S, D1, D2, Money.of(5_000, Money.KRW));
    }

    @Test
    void 완료된_Run의_측정을_찾지_못하면_0으로_떨어지지_않고_멈춘다() {
        var capture = new WalkForwardStudy.FunnelCapture((p, s) -> IDLE);
        capture.create(PROFILE, seed() + 1); // 실행기의 시드 파생이 바뀐 상황: 키가 어긋난다
        assertThatThrownBy(() -> capture.probe(completed(List.of(), Set.of()), SPEC_SEED, D1, D2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("진입 깔때기를 Run과 잇지 못했습니다")
                .hasMessageContaining("R-1");
    }

    @Test
    void 전략을_만들기_전에_실패한_Run은_측정_0이다() {
        var capture = new WalkForwardStudy.FunnelCapture((p, s) -> IDLE);
        assertThat(capture.probe(failed(), SPEC_SEED, D1, D2)).isEqualTo(WalkForwardStudy.Probe.ZERO);
    }

    @Test
    void 측정하지_않는_전략은_깔때기_0이고_구간_끝_정리_밖_왕복은_미상으로_센다() {
        var capture = new WalkForwardStudy.FunnelCapture((p, s) -> IDLE);
        capture.create(PROFILE, seed());
        WalkForwardStudy.Probe probe = capture.probe(completed(List.of(trip("L1"), trip("L2"), trip("L3")), Set.of("L2")),
                SPEC_SEED, D1, D2);
        assertThat(probe.funnel()).isEqualTo(EntryFunnel.Totals.ZERO);
        assertThat(probe.exits()).isEqualTo(new ExitMix(0, 0, 0, 0, 0, 0, 1, 2));
        assertThat(probe.exits().total()).as("합 = 왕복 수").isEqualTo(3);
    }
}
