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
