package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.Page;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.run.SimulationReport;
import java.util.Optional;

/**
 * 대시보드가 읽는 실험 결과(UI-02). 실험 결과는 불변이므로 같은 ID를 다시 등록할 수 없다.
 *
 * <p>조회는 화면 질의 단위(목록 한 쪽, 상세, 자산 곡선, 체결 한 쪽 …)로 나눈다. DB 구현(ADR-004 4장 테이블)은 각 메서드를
 * 질의 하나로 옮기면 되고, 실험 전체를 메모리로 되살릴 필요가 없다. 지금 구현은 {@link InMemoryExperimentCatalog}뿐이다.
 * 실험이나 계좌가 없으면 빈 Optional이다.
 */
public interface ExperimentCatalog {

    /**
     * @throws IllegalArgumentException 명세와 결과가 맞지 않거나(ID, Run 수) 같은 ID가 이미 있을 때
     */
    void register(ExperimentSpec spec, SimulationReport report);

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
}
