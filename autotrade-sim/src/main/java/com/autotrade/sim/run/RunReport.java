package com.autotrade.sim.run;

import com.autotrade.sim.RunResult;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.strategy.StrategyProfile;
import java.util.List;
import java.util.Objects;

/**
 * Run 하나의 상세 결과: 요약, 체결 원장(virtual_trade_log), 자산 곡선(equity_curve), 왕복 거래, 거절 기록.
 * 실패한 Run도 실패 직전까지의 기록을 담는다. 저장은 실험실 프로세스가 한다(ADR-004 4장).
 *
 * @param profile 주문 시점 전략 파라미터 스냅샷(parameters_snapshot). Run 동안 바뀌지 않는다
 */
public record RunReport(
        RunResult summary,
        StrategyProfile profile,
        List<AppliedFill> fills,
        List<EquityPoint> equityCurve,
        List<RoundTrip> roundTrips,
        List<OrderRejection> rejections) {

    public RunReport {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(profile, "profile");
        fills = List.copyOf(fills);
        equityCurve = List.copyOf(equityCurve);
        roundTrips = List.copyOf(roundTrips);
        rejections = List.copyOf(rejections);
    }
}
