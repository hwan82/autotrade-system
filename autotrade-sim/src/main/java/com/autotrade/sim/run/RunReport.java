package com.autotrade.sim.run;

import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.strategy.StrategyProfile;
import java.util.List;
import java.util.Objects;

/**
 * Run 하나의 상세 결과: 요약, 체결 원장(virtual_trade_log), 자산 곡선(equity_curve), 왕복 거래, 거절 기록, 날짜별 선정
 * 기록(run_selection_day). 실패한 Run도 실패 직전까지의 기록을 담는다. 저장은 실험실 프로세스가 한다(ADR-004 4장).
 *
 * @param combination 이 계좌의 조합. 전략 파라미터는 주문 시점 스냅샷(parameters_snapshot)이고 Run 동안 바뀌지 않는다
 * @param selectionDays 선정 있는 조합이면 이벤트가 있던 거래일마다 한 줄(거래일 오름차순), 선정이 없으면 빈 목록
 */
public record RunReport(
        RunResult summary,
        Combination combination,
        List<AppliedFill> fills,
        List<EquityPoint> equityCurve,
        List<RoundTrip> roundTrips,
        List<OrderRejection> rejections,
        List<RunSelectionDay> selectionDays) {

    public RunReport {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(combination, "combination");
        fills = List.copyOf(fills);
        equityCurve = List.copyOf(equityCurve);
        roundTrips = List.copyOf(roundTrips);
        rejections = List.copyOf(rejections);
        selectionDays = List.copyOf(selectionDays);
        if (!combination.hasSelection() && !selectionDays.isEmpty()) {
            throw new IllegalArgumentException("선정 없는 조합에는 선정 기록이 없습니다: " + summary.runId());
        }
    }

    /** 선정 없는 조합의 Run(조합 도입 전 계약). */
    public RunReport(RunResult summary, StrategyProfile profile, List<AppliedFill> fills, List<EquityPoint> equityCurve,
            List<RoundTrip> roundTrips, List<OrderRejection> rejections) {
        this(summary, Combination.of(profile), fills, equityCurve, roundTrips, rejections, List.of());
    }

    /** 이 계좌의 전략 프로필. */
    public StrategyProfile profile() {
        return combination.profile();
    }
}
