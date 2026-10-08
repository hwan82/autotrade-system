package com.autotrade.app.lab.experiment;

import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.run.SimulationReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** 프로세스 메모리에만 두는 실험 목록. 실험실을 다시 띄우면 비어 있다(DB 저장 전 임시 구현). */
public final class InMemoryExperimentCatalog implements ExperimentCatalog {

    private final TreeMap<String, Entry> entries = new TreeMap<>(Comparator.reverseOrder());

    @Override
    public synchronized void register(ExperimentSpec spec, SimulationReport report) {
        Entry entry = new Entry(spec, report);
        if (entries.putIfAbsent(spec.id().value(), entry) != null) {
            throw new IllegalArgumentException("이미 등록된 실험 ID입니다: " + spec.id());
        }
    }

    @Override
    public synchronized List<Entry> list() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    @Override
    public synchronized Optional<Entry> find(ExperimentId id) {
        return Optional.ofNullable(entries.get(id.value()));
    }
}
