package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.Page;
import com.autotrade.marketdata.symbols.SymbolNames;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationReport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 프로세스 메모리에만 두는 실험 목록. 실험실을 다시 띄우면 비어 있다(DB 저장 전 임시 구현).
 * 종목명 표는 응답에 이름을 붙이는 데만 쓰며 결과에는 영향을 주지 않는다.
 */
public final class InMemoryExperimentCatalog implements ExperimentCatalog {

    private final TreeMap<String, Entry> entries = new TreeMap<>(Comparator.reverseOrder());

    private final SymbolNames names;

    /** 종목명을 모르는 카탈로그. */
    public InMemoryExperimentCatalog() {
        this(SymbolNames.empty());
    }

    public InMemoryExperimentCatalog(SymbolNames names) {
        this.names = Objects.requireNonNull(names, "names");
    }

    private record Entry(ExperimentSpec spec, SimulationReport report, Map<String, SelectionResult> results) {
    }

    @Override
    public synchronized void register(
            ExperimentSpec spec, SimulationReport report, Collection<SelectionResult> selectionResults) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(report, "report");
        if (!spec.id().equals(report.result().experimentId())) {
            throw new IllegalArgumentException("명세와 결과의 실험 ID가 다릅니다: " + spec.id());
        }
        if (spec.combinations().size() != report.runs().size()) {
            throw new IllegalArgumentException("조합 수와 Run 수가 다릅니다: " + spec.id());
        }
        Set<String> runIds = new HashSet<>();
        for (RunReport r : report.runs()) {
            if (!runIds.add(r.summary().runId())) {
                throw new IllegalArgumentException("같은 runId가 두 번 있습니다: " + r.summary().runId());
            }
        }
        Set<String> selectionHashes = new HashSet<>();
        spec.combinations().forEach(c -> c.selection().ifPresent(sel -> selectionHashes.add(sel.selectionHash())));
        Map<String, SelectionResult> results = new HashMap<>();
        for (SelectionResult r : selectionResults) {
            if (!selectionHashes.contains(r.selection().hash())) {
                throw new IllegalArgumentException("실험 조합에 없는 선정의 결과입니다: " + r.selection().id() + "@"
                        + r.selection().version());
            }
            results.putIfAbsent(r.contentHash(), r);
        }
        if (entries.putIfAbsent(spec.id().value(), new Entry(spec, report, Map.copyOf(results))) != null) {
            throw new IllegalArgumentException("이미 등록된 실험 ID입니다: " + spec.id());
        }
    }

    @Override
    public synchronized Page<ExperimentViews.Summary> summaries(int page, int size) {
        List<ExperimentViews.Summary> all = new ArrayList<>();
        entries.values().forEach(e -> all.add(ExperimentViews.Summary.of(e.spec(), e.report())));
        return Page.of(all, page, size);
    }

    @Override
    public synchronized Optional<ExperimentViews.Detail> detail(ExperimentId id) {
        return entry(id).map(e -> ExperimentViews.Detail.of(e.spec(), e.report(), e.results()));
    }

    @Override
    public synchronized Optional<ExperimentViews.EquityOverlay> equity(ExperimentId id) {
        return entry(id).map(e -> ExperimentViews.EquityOverlay.of(e.spec(), e.report()));
    }

    @Override
    public synchronized Optional<Page<ExperimentViews.FillRow>> fills(ExperimentId id, String runId, int page, int size) {
        return run(id, runId).map(r -> ExperimentViews.fillPage(r.fills(), page, size, names));
    }

    @Override
    public synchronized Optional<Page<ExperimentViews.RoundTripRow>> roundTrips(
            ExperimentId id, String runId, int page, int size) {
        return run(id, runId).map(r -> Page.of(
                r.roundTrips().stream().map(t -> ExperimentViews.RoundTripRow.of(t, names)).toList(), page, size));
    }

    @Override
    public synchronized Optional<ExperimentViews.RejectionPage> rejections(
            ExperimentId id, String runId, String stage, String code, int page, int size) {
        return run(id, runId).map(r -> ExperimentViews.rejectionPage(r.rejections(), stage, code, page, size, names));
    }

    @Override
    public synchronized Optional<ExperimentViews.RunSummary> runSummary(ExperimentId id, String runId) {
        return entry(id).flatMap(e -> e.report().runs().stream()
                .filter(r -> r.summary().runId().equals(runId)).findFirst()
                .map(r -> ExperimentViews.RunSummary.of(e.spec(), r, names)));
    }

    @Override
    public synchronized Optional<SelectionViews.CrossTable> crossTable(ExperimentId id, String metric, String rowControl) {
        return entry(id).map(e -> SelectionViews.CrossTable.of(e.report().runs(), metric, rowControl));
    }

    @Override
    public synchronized Optional<Page<SelectionViews.SelectionDayRow>> selectionDays(
            ExperimentId id, String runId, int page, int size) {
        return entry(id).flatMap(e -> e.report().runs().stream()
                .filter(r -> r.summary().runId().equals(runId)).findFirst()
                .map(r -> Page.of(r.selectionDays().stream()
                        .map(d -> SelectionViews.SelectionDayRow.of(d, e.results())).toList(), page, size)));
    }

    @Override
    public synchronized Optional<SelectionViews.SelectionResultView> selectionResult(ExperimentId id, String resultHash) {
        return result(id, resultHash).map(r -> SelectionViews.SelectionResultView.of(r, names));
    }

    @Override
    public synchronized Optional<Page<SelectionViews.EvaluationRow>> selectionEvaluations(
            ExperimentId id, String resultHash, String filter, String reason, int page, int size) {
        SelectionViews.EvaluationFilter f = SelectionViews.EvaluationFilter.parse(filter);
        return result(id, resultHash)
                .map(r -> Page.of(SelectionViews.evaluations(r, names, f, reason), page, size));
    }

    private Optional<SelectionResult> result(ExperimentId id, String resultHash) {
        return entry(id).flatMap(e -> Optional.ofNullable(e.results().get(resultHash)));
    }

    private Optional<Entry> entry(ExperimentId id) {
        return Optional.ofNullable(entries.get(id.value()));
    }

    private Optional<RunReport> run(ExperimentId id, String runId) {
        return entry(id).flatMap(e -> e.report().runs().stream()
                .filter(r -> r.summary().runId().equals(runId)).findFirst());
    }
}
