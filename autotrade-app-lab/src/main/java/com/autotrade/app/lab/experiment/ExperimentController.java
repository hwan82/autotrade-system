package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.ApiException;
import com.autotrade.app.lab.web.Page;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.RunReport;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    public List<ExperimentViews.Summary> list() {
        return catalog.list().stream().map(ExperimentViews.Summary::of).toList();
    }

    @GetMapping("/{id}")
    public ExperimentViews.Detail detail(@PathVariable String id) {
        return ExperimentViews.Detail.of(entry(id));
    }

    @GetMapping("/{id}/equity")
    public ExperimentViews.EquityOverlay equity(@PathVariable String id) {
        return ExperimentViews.EquityOverlay.of(entry(id));
    }

    @GetMapping("/{id}/runs/{runId}/fills")
    public Page<ExperimentViews.FillRow> fills(
            @PathVariable String id, @PathVariable String runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        // 실행기가 남긴 순서(체결 이벤트 순)를 그대로 쓴다
        return Page.of(run(id, runId).fills().stream().map(ExperimentViews.FillRow::of).toList(), page, size);
    }

    @GetMapping("/{id}/runs/{runId}/round-trips")
    public Page<ExperimentViews.RoundTripRow> roundTrips(
            @PathVariable String id, @PathVariable String runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return Page.of(run(id, runId).roundTrips().stream().map(ExperimentViews.RoundTripRow::of).toList(), page, size);
    }

    /**
     * 거절 사유 로그. {@code stage}·{@code code}로 거를 수 있고, 거르기 전 전체의 사유별 건수를 함께 준다.
     */
    @GetMapping("/{id}/runs/{runId}/rejections")
    public RejectionPage rejections(
            @PathVariable String id, @PathVariable String runId,
            @RequestParam(required = false) String stage, @RequestParam(required = false) String code,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        List<OrderRejection> all = run(id, runId).rejections();
        List<ExperimentViews.RejectionRow> filtered = all.stream()
                .filter(j -> stage == null || stage.isBlank() || j.stage().equals(stage))
                .filter(j -> code == null || code.isBlank() || j.code().equals(code))
                .map(ExperimentViews.RejectionRow::of)
                .toList();
        return new RejectionPage(Page.of(filtered, page, size), counts(all));
    }

    /** 건수 내림차순, 같으면 stage·code 이름순(결과가 매번 같은 순서). */
    static List<ExperimentViews.RejectionCount> counts(List<OrderRejection> all) {
        Map<String, Long> byKey = all.stream().collect(Collectors.groupingBy(
                j -> j.stage() + ":" + j.code(), TreeMap::new, Collectors.counting()));
        return byKey.entrySet().stream()
                .map(e -> {
                    int colon = e.getKey().indexOf(':');
                    return new ExperimentViews.RejectionCount(
                            e.getKey().substring(0, colon), e.getKey().substring(colon + 1), e.getValue());
                })
                .sorted(Comparator.comparingLong(ExperimentViews.RejectionCount::count).reversed()
                        .thenComparing(ExperimentViews.RejectionCount::stage)
                        .thenComparing(ExperimentViews.RejectionCount::code))
                .toList();
    }

    public record RejectionPage(Page<ExperimentViews.RejectionRow> page, List<ExperimentViews.RejectionCount> counts) {
    }

    private ExperimentCatalog.Entry entry(String id) {
        ExperimentId experimentId;
        try {
            experimentId = new ExperimentId(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("실험 ID 형식이 아닙니다");
        }
        return catalog.find(experimentId).orElseThrow(() -> ApiException.notFound("실험이 없습니다: " + id));
    }

    private RunReport run(String id, String runId) {
        Map<String, RunReport> runs = entry(id).report().runs().stream()
                .collect(Collectors.toMap(r -> r.summary().runId(), Function.identity()));
        RunReport run = runs.get(runId);
        if (run == null) {
            throw ApiException.notFound("이 실험에 없는 계좌입니다");
        }
        return run;
    }
}
