package com.autotrade.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 한 주기의 수집 결과.
 *
 * @param at 수집 시각(주입된 시계)
 * @param readings 이름순 지표 값
 * @param levels 임계가 있는 지표의 현재 수준(지표 이름과 대상 → 수준)
 * @param failedSources 이번 주기에 예외를 낸 수집원 이름
 */
public record MetricSnapshot(Instant at, List<Reading> readings, Map<String, Level> levels, List<String> failedSources) {

    public MetricSnapshot {
        Objects.requireNonNull(at, "at");
        readings = List.copyOf(readings);
        levels = Map.copyOf(levels);
        failedSources = List.copyOf(failedSources);
    }
}
