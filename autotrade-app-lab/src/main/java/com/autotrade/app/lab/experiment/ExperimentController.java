package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.app.lab.web.Page;
import com.autotrade.sim.ExperimentId;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 실험 비교 대시보드 조회 API(UI-02, ADR-005 5장). 읽기 전용이다. */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentCatalog catalog;

    public ExperimentController(ExperimentCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public Page<ExperimentViews.Summary> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return catalog.summaries(page, size);
    }

    @GetMapping("/{id}")
    public ExperimentViews.Detail detail(@PathVariable String id) {
        return found(catalog.detail(id(id)), "실험이 없습니다");
    }

    @GetMapping("/{id}/equity")
    public ExperimentViews.EquityOverlay equity(@PathVariable String id) {
        return found(catalog.equity(id(id)), "실험이 없습니다");
    }

    /** 계좌 상세 머리 요약(기간, 시드, 평가금액, 수익률, 누적 실현손익, 비용, 기간 끝 보유 종목). */
    @GetMapping("/{id}/runs/{runId}/summary")
    public ExperimentViews.RunSummary runSummary(@PathVariable String id, @PathVariable String runId) {
        return found(catalog.runSummary(id(id), runId), "실험이나 계좌가 없습니다");
    }

    @GetMapping("/{id}/runs/{runId}/fills")
    public Page<ExperimentViews.FillRow> fills(
            @PathVariable String id, @PathVariable String runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return found(catalog.fills(id(id), runId, page, size), "실험이나 계좌가 없습니다");
    }

    @GetMapping("/{id}/runs/{runId}/round-trips")
    public Page<ExperimentViews.RoundTripRow> roundTrips(
            @PathVariable String id, @PathVariable String runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return found(catalog.roundTrips(id(id), runId, page, size), "실험이나 계좌가 없습니다");
    }

    /** 거절 사유 로그. {@code stage}·{@code code}로 거를 수 있고, 거르기 전 전체의 사유별 건수를 함께 준다. */
    @GetMapping("/{id}/runs/{runId}/rejections")
    public ExperimentViews.RejectionPage rejections(
            @PathVariable String id, @PathVariable String runId,
            @RequestParam(required = false) String stage, @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return found(catalog.rejections(id(id), runId, stage, code, page, size), "실험이나 계좌가 없습니다");
    }

    /** 선정 × 매매지표 교차표. {@code rowControl}은 행 대조 열(전략 {@code id@version}, 보통 무작위 진입 프로필). */
    @GetMapping("/{id}/cross-table")
    public SelectionViews.CrossTable crossTable(
            @PathVariable String id, @RequestParam(defaultValue = "totalReturn") String metric,
            @RequestParam(required = false) String rowControl) {
        try {
            return found(catalog.crossTable(id(id), metric,
                    rowControl == null || rowControl.isBlank() ? null : rowControl), "실험이 없습니다");
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** 선정 있는 계좌의 날짜별 선정 기록(run_selection_day). */
    @GetMapping("/{id}/runs/{runId}/selection-days")
    public Page<SelectionViews.SelectionDayRow> selectionDays(
            @PathVariable String id, @PathVariable String runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return found(catalog.selectionDays(id(id), runId, page, size), "실험이나 계좌가 없습니다");
    }

    /** 이 실험이 쓴 선정 결과 하나(입력 기준일, 선정 종목, 탈락 사유별 건수, 정정 대체 규칙 적용 수). */
    @GetMapping("/{id}/selection-results/{hash}")
    public SelectionViews.SelectionResultView selectionResult(@PathVariable String id, @PathVariable String hash) {
        return found(catalog.selectionResult(id(id), resultHash(hash)), "이 실험에 등록된 선정 결과가 없습니다");
    }

    /** 선정 결과의 평가 행. {@code filter}: all, passed, failed, fallback. {@code reason}: 탈락 사유 코드. */
    @GetMapping("/{id}/selection-results/{hash}/evaluations")
    public Page<SelectionViews.EvaluationRow> selectionEvaluations(
            @PathVariable String id, @PathVariable String hash,
            @RequestParam(required = false) String filter, @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        try {
            return found(catalog.selectionEvaluations(id(id), resultHash(hash), filter, reason, page, size),
                    "이 실험에 등록된 선정 결과가 없습니다");
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static String resultHash(String hash) {
        return SelectionViews.checkedHash(hash).orElseThrow(() -> ApiException.badRequest("결과 해시 형식이 아닙니다"));
    }

    private static ExperimentId id(String id) {
        try {
            return new ExperimentId(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("실험 ID 형식이 아닙니다");
        }
    }

    private static <T> T found(Optional<T> value, String message) {
        return value.orElseThrow(() -> ApiException.notFound(message));
    }
}
