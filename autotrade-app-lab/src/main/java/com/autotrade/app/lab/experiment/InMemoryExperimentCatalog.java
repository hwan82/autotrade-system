package com.autotrade.app.lab.experiment;

import com.autotrade.app.lab.web.Page;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 프로세스 메모리에만 두는 실험 목록. 실험실을 다시 띄우면 비어 있다(DB 저장 전 임시 구현). */
public final class InMemoryExperimentCatalog implements ExperimentCatalog {

    private final TreeMap<String, Entry> entries = new TreeMap<>(Comparator.reverseOrder());

    private record Entry(ExperimentSpec spec, SimulationReport report) {
    }

    @Override
    public synchronized void register(ExperimentSpec spec, SimulationReport report) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(report, "report");
        if (!spec.id().equals(report.result().experimentId())) {
            throw new IllegalArgumentException("명세와 결과의 실험 ID가 다릅니다: " + spec.id());
        }
        if (spec.profiles().size() != report.runs().size()) {
            throw new IllegalArgumentException("프로필 수와 Run 수가 다릅니다: " + spec.id());
        }
        Set<String> runIds = new HashSet<>();
        for (RunReport r : report.runs()) {
            if (!runIds.add(r.summary().runId())) {
                throw new IllegalArgumentException("같은 runId가 두 번 있습니다: " + r.summary().runId());
            }
        }
        if (entries.putIfAbsent(spec.id().value(), new Entry(spec, report)) != null) {
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
        return entry(id).map(e -> ExperimentViews.Detail.of(e.spec(), e.report()));
    }

    @Override
    public synchronized Optional<ExperimentViews.EquityOverlay> equity(ExperimentId id) {
        return entry(id).map(e -> ExperimentViews.EquityOverlay.of(e.spec(), e.report()));
    }

    @Override
    public synchronized Optional<Page<ExperimentViews.FillRow>> fills(ExperimentId id, String runId, int page, int size) {
        return run(id, runId).map(r -> Page.of(r.fills().stream().map(ExperimentViews.FillRow::of).toList(), page, size));
    }

    @Override
    public synchronized Optional<Page<ExperimentViews.RoundTripRow>> roundTrips(
            ExperimentId id, String runId, int page, int size) {
        return run(id, runId).map(r -> Page.of(
                r.roundTrips().stream().map(ExperimentViews.RoundTripRow::of).toList(), page, size));
    }

    @Override
    public synchronized Optional<ExperimentViews.RejectionPage> rejections(
            ExperimentId id, String runId, String stage, String code, int page, int size) {
        return run(id, runId).map(r -> {
            List<ExperimentViews.RejectionRow> filtered = r.rejections().stream()
                    .filter(j -> stage == null || stage.isBlank() || j.stage().equals(stage))
                    .filter(j -> code == null || code.isBlank() || j.code().equals(code))
                    .map(ExperimentViews.RejectionRow::of)
                    .toList();
            return new ExperimentViews.RejectionPage(Page.of(filtered, page, size), counts(r.rejections()));
        });
    }

    /** 건수 내림차순, 같으면 stage·code 이름순(결과가 매번 같은 순서). */
    static List<ExperimentViews.RejectionCount> counts(List<OrderRejection> all) {
        record Key(String stage, String code) {
        }
        Map<Key, Long> byKey = all.stream().collect(Collectors.groupingBy(
                j -> new Key(j.stage(), j.code()), Collectors.counting()));
        return byKey.entrySet().stream()
                .map(e -> new ExperimentViews.RejectionCount(e.getKey().stage(), e.getKey().code(), e.getValue()))
                .sorted(Comparator.comparingLong(ExperimentViews.RejectionCount::count).reversed()
                        .thenComparing(ExperimentViews.RejectionCount::stage)
                        .thenComparing(ExperimentViews.RejectionCount::code))
                .toList();
    }

    private Optional<Entry> entry(ExperimentId id) {
        return Optional.ofNullable(entries.get(id.value()));
    }

    private Optional<RunReport> run(ExperimentId id, String runId) {
        return entry(id).flatMap(e -> e.report().runs().stream()
                .filter(r -> r.summary().runId().equals(runId)).findFirst());
    }
}
