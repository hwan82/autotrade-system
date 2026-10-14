package com.autotrade.sim.walkforward;

import com.autotrade.marketdata.daily.SnapshotListedShares;
import com.autotrade.selection.calc.SnapshotSelectionData;
import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.daily.SnapshotTradingDays;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.walkforward.WalkForwardWindows.Window;
import com.autotrade.strategy.catalog.EntryFunnel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 워크포워드 판정 계산 결과(보고서 입력). 파일 내용을 만들기 위한 값 묶음이며 {@link WalkForwardReport}만 읽는다.
 *
 * @param validationRuns 후보 순서의 구간별 검증 Run(기본 비용)
 * @param nulls 대조군 쌍 텍스트("선정 x 대조군") → 요약
 * @param windowInfo 구간별 데이터셋 정보(구간 순)
 */
record WalkForwardResult(
        WalkForwardPlan plan,
        SnapshotSelectionData selectionData,
        SnapshotTradingDays.Derived tradingDays,
        SnapshotListedShares shares,
        List<Window> windows,
        List<Candidate> candidates,
        List<WalkForwardJudge.Judgement> judgements,
        List<List<WindowRun>> validationRuns,
        MarketIndex index,
        List<BigDecimal> idxCapWindows,
        List<BigDecimal> idxEqualWindows,
        BigDecimal idxCapPooled,
        BigDecimal idxEqualPooled,
        CostModel cost,
        CostModel stressCost,
        String costTableHash,
        ExecutionSettings execution,
        Map<String, NullSummary> nulls,
        List<WindowInfo> windowInfo,
        String codeText,
        String blacklistFingerprint,
        int blacklistSize) {

    /** 후보 조합 하나와 그 대조군·변형. */
    record Candidate(String text, Combination combination, String pairKey, Combination control, Combination low,
            Combination high, String perturbKey, String perturbNote) {
    }

    /**
     * 대조군 분포 요약. 값은 시드별 왕복당 순손익(원). {@code funnel}은 모든 시드·검증 구간 Run의 진입 깔때기 합.
     * {@code avgHoldingDays}는 완료된 시드 전체 왕복의 평균 보유 기간(진입일~청산일 달력일, 후보 리더보드와 같은 정의). 왕복이 없으면 null.
     */
    record NullSummary(String pairKey, int usable, int failed, int zeroTrip, BigDecimal min, BigDecimal median, BigDecimal max,
            EntryFunnel.Totals funnel, BigDecimal avgHoldingDays) {
    }

    /** 구간별 데이터 정보. */
    record WindowInfo(Window window, LocalDate trainWarmupStart, LocalDate testWarmupStart, int trainDays, int testDays,
            LocalDate firstTestDay, LocalDate lastTestDay, String trainDataSet, String testDataSet, int symbols) {
    }
}
