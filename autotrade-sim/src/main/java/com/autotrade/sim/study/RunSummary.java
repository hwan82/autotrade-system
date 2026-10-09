package com.autotrade.sim.study;

import com.autotrade.sim.RunStatus;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import java.util.List;
import java.util.SortedMap;

/**
 * 비교에 쓰는 Run 하나의 요약. 체결 원장 전체는 들고 있지 않는다(메모리). 원장은 실행기 {@code RunReport}에 있다.
 *
 * @param stopLossSells 손절 의도로 나간 매도 체결 수(청산 사유 분포의 일부. 나머지 사유는 거래 로그에 사유 코드가 생기면 나눈다)
 * @param rejectionsByCode 거절 사유별 건수(단계:코드)
 */
public record RunSummary(
        String profileId,
        String profileHash,
        RunStatus status,
        String failureReason,
        RunMetrics metrics,
        List<EquityPoint> equity,
        int stopLossSells,
        SortedMap<String, Integer> rejectionsByCode) {

    public RunSummary {
        equity = List.copyOf(equity);
    }

    public boolean completed() {
        return status == RunStatus.COMPLETED && metrics != null;
    }
}
