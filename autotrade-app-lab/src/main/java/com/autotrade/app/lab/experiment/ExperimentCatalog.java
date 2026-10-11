package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.Page;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.run.SimulationReport;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 대시보드가 읽는 실험 결과(UI-02). 실험 결과는 불변이므로 같은 ID를 다시 등록할 수 없다.
 *
 * <p>조회는 화면 질의 단위(목록 한 쪽, 상세, 자산 곡선, 체결 한 쪽 …)로 나눈다. DB 구현(ADR-004 4장 테이블)은 각 메서드를
 * 질의 하나로 옮기면 되고, 실험 전체를 메모리로 되살릴 필요가 없다. 지금 구현은 {@link InMemoryExperimentCatalog}뿐이다.
 * 실험이나 계좌가 없으면 빈 Optional이다.
 */
public interface ExperimentCatalog {

    /** 선정 결과 본문 없이 등록한다. 선정 있는 계좌의 날짜별 기록은 보이지만 결과 본문(평가 행)은 조회할 수 없다. */
    default void register(ExperimentSpec spec, SimulationReport report) {
        register(spec, report, List.of());
    }

    /**
     * @param selectionResults 실험이 쓴 선정 결과 본문(실험실은 {@code CalculatedSelectionSource.results()}로 얻는다).
     *     내용 해시로 찾는다
     * @throws IllegalArgumentException 명세와 결과가 맞지 않거나(ID, Run 수), 같은 ID가 이미 있거나, 실험 조합에 없는 선정의
     *     결과가 섞였을 때
     */
    void register(ExperimentSpec spec, SimulationReport report, Collection<SelectionResult> selectionResults);

    /** 실험 ID 내림차순. */
    Page<ExperimentViews.Summary> summaries(int page, int size);

    Optional<ExperimentViews.Detail> detail(ExperimentId id);

    Optional<ExperimentViews.EquityOverlay> equity(ExperimentId id);

    /** 실행기가 남긴 순서(체결 이벤트 순). */
    Optional<Page<ExperimentViews.FillRow>> fills(ExperimentId id, String runId, int page, int size);

    Optional<Page<ExperimentViews.RoundTripRow>> roundTrips(ExperimentId id, String runId, int page, int size);

    /**
     * @param stage 거를 단계(RISK·EXCHANGE). null이나 빈 값이면 거르지 않음
     * @param code 거를 사유 코드. null이나 빈 값이면 거르지 않음
     */
    Optional<ExperimentViews.RejectionPage> rejections(
            ExperimentId id, String runId, String stage, String code, int page, int size);

    /**
     * 선정 × 매매지표 교차표(UI-02).
     *
     * @param metric 리더보드 지표 이름(예: totalReturn)
     * @param rowControl 행 대조 열(전략 {@code id@version}). null이면 행 대조 차이 없음
     * @throws IllegalArgumentException 모르는 지표이거나 실험에 없는 대조 열일 때
     */
    Optional<SelectionViews.CrossTable> crossTable(ExperimentId id, String metric, String rowControl);

    /** 선정 있는 계좌의 날짜별 선정 기록(거래일 오름차순). 선정 없는 계좌면 빈 쪽. */
    Optional<Page<SelectionViews.SelectionDayRow>> selectionDays(ExperimentId id, String runId, int page, int size);

    /** 이 실험이 쓴 선정 결과 하나. 본문이 등록되지 않았으면 빈 Optional. */
    Optional<SelectionViews.SelectionResultView> selectionResult(ExperimentId id, String resultHash);

    /**
     * 선정 결과의 평가 행(종목 코드순).
     *
     * @param filter all, passed, failed, fallback(정정 대체 규칙 적용). null이면 all
     * @param reason 이 탈락 사유가 있는 행만. null이나 빈 값이면 거르지 않음
     */
    Optional<Page<SelectionViews.EvaluationRow>> selectionEvaluations(
            ExperimentId id, String resultHash, String filter, String reason, int page, int size);

    /** 계좌 상세 머리의 요약(기간, 시드, 마지막 평가금액·현금, 총수익률, 누적 실현손익, 비용, 기간 끝 보유 종목). */
    Optional<ExperimentViews.RunSummary> runSummary(ExperimentId id, String runId);
}
