package com.autotrade.monitorsources;

import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitor.Threshold;
import com.autotrade.monitor.Threshold.Direction;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 표준 수집원과 기본 임계로 에이전트를 만든다. 다른 프로젝트에서 붙이는 방법(MON-05 "의존성 추가와 설정만으로 이식"):
 *
 * <pre>{@code
 * MonitorAgent agent = StandardSources.agent(Clock.systemUTC(), mount -> true, List.of(), 720, 200);
 * agent.addListener((snapshot, events) -> ...);
 * agent.start(Duration.ofSeconds(5));
 * }</pre>
 */
public final class StandardSources {

    private StandardSources() {}

    /**
     * 기본 임계. 값은 모두 {@code [가정]}이다(원본 기획끼리 값이 달라 하나로 정하지 못함, 기획서 MON-05). 디스크는 사용률이 아니라
     * 여유 GB로 본다(큰 드라이브는 90%여도 여유가 크다). 20GB는 실시간 모의 입력 기록 하한(D-076)과 같다.
     */
    public static List<Threshold> defaultThresholds() {
        return List.of(
                new Threshold(MetricNames.CPU_SYSTEM, Direction.ABOVE, 85, 95, 3),
                new Threshold(MetricNames.MEMORY_USED, Direction.ABOVE, 85, 95, 3),
                new Threshold(MetricNames.DISK_FREE_GB, Direction.BELOW, 50, 20, 1),
                new Threshold(MetricNames.JVM_HEAP_USED, Direction.ABOVE, 85, 95, 3),
                new Threshold(MetricNames.JVM_GC_TIME, Direction.ABOVE, 10, 25, 3),
                new Threshold(MetricNames.JVM_THREADS, Direction.ABOVE, 500, 1000, 3));
    }

    /** 기본 임계에 덮어쓸 임계를 합친다(같은 지표는 덮어쓴 쪽, 새 지표는 더함). */
    public static List<Threshold> merge(List<Threshold> base, List<Threshold> overrides) {
        Map<String, Threshold> byMetric = new LinkedHashMap<>();
        base.forEach(t -> byMetric.put(t.metric(), t));
        overrides.forEach(t -> byMetric.put(t.metric(), t));
        return new ArrayList<>(byMetric.values());
    }

    /** PC 전체와 이 JVM 수집원. */
    public static List<MetricSource> sources(Clock clock, Predicate<String> diskFilter) {
        return List.of(new SystemSource(clock, diskFilter), new JvmSource());
    }

    /** 표준 수집원, 기본 임계에 {@code overrides}를 덮은 임계로 만든 에이전트(시작 전). */
    public static MonitorAgent agent(Clock clock, Predicate<String> diskFilter, List<Threshold> overrides, int historySize,
            int eventHistorySize) {
        return new MonitorAgent(clock, sources(clock, diskFilter), merge(defaultThresholds(), overrides), historySize,
                eventHistorySize);
    }
}
