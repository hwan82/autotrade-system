package com.autotrade.sim.walkforward;

import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.run.RunReport;
import com.autotrade.strategy.catalog.EntryFunnel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.Objects;

/**
 * 구간 하나에서 돈 Run 하나의 판정용 요약(실행기의 {@link RunReport} 상세를 오래 들고 있지 않으려고 필요한 것만 남긴다).
 *
 * @param window 구간 번호(1부터)
 * @param failure 실패 사유. 완료됐으면 빈 문자열
 * @param metrics 구간 안 지표. 실패면 null
 * @param windowEndLots 구간 끝 정리(WINDOW_END)로 생긴 왕복의 lotId
 * @param fills 체결 원장. 비용·회전율 계산이 필요한 Run만 담고 아니면 빈 목록
 * @param funnel 구간 안 진입 깔때기(신호·진입·한도 거절, 예열 제외). 측정하지 않는 전략이면 0
 */
public record WindowRun(int window, String failure, RunMetrics metrics, List<EquityPoint> equity,
        List<RoundTrip> roundTrips, List<AppliedFill> fills, Set<String> windowEndLots, EntryFunnel.Totals funnel) {

    public WindowRun {
        Objects.requireNonNull(failure, "failure");
        equity = List.copyOf(equity);
        roundTrips = List.copyOf(roundTrips);
        fills = List.copyOf(fills);
        windowEndLots = Set.copyOf(windowEndLots);
        Objects.requireNonNull(funnel, "funnel");
    }

    public static WindowRun of(int window, RunReport r, boolean keepFills, EntryFunnel.Totals funnel) {
        boolean ok = r.summary().status() == RunStatus.COMPLETED && r.summary().metrics() != null;
        return new WindowRun(window, ok ? "" : (r.summary().failureReason().isEmpty() ? "실패" : r.summary().failureReason()),
                r.summary().metrics(), r.equityCurve(), r.roundTrips(), keepFills ? r.fills() : List.of(), r.windowEndLots(), funnel);
    }

    public boolean completed() {
        return failure.isEmpty() && metrics != null;
    }

    /** 관측 거래일 수(자산 곡선 점 수). */
    public int days() {
        return equity.size();
    }

    /** 최종 평가금액(실패면 null). */
    public BigDecimal finalEquity() {
        return metrics == null ? null : metrics.finalEquity().amount();
    }

    /** 구간 끝 정리(WINDOW_END)로 생긴 왕복 수. */
    public int windowEndTrips() {
        return (int) roundTrips.stream().filter(t -> windowEndLots.contains(t.lotId())).count();
    }

    /** 구간 끝 정리 왕복의 손익 합(원). */
    public BigDecimal windowEndPnl() {
        BigDecimal sum = BigDecimal.ZERO;
        for (RoundTrip t : roundTrips) {
            if (windowEndLots.contains(t.lotId())) {
                sum = sum.add(t.pnl().amount());
            }
        }
        return sum;
    }
}
