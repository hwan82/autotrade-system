package com.autotrade.monitorsources;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.monitor.Level;
import com.autotrade.monitor.MetricSnapshot;
import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitor.Reading;
import com.autotrade.monitor.Threshold;
import com.autotrade.monitor.Threshold.Direction;
import com.autotrade.monitor.ThresholdEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * MON-05 인수 기준 "샘플 앱에 붙였을 때 지표 노출, 임계 초과 주입 시 이벤트 발생". 이 시험 JVM을 샘플 앱으로 보고 표준
 * 수집원을 붙인다(실제 OS·JVM 값을 읽는다).
 */
class SampleAppAttachTest {

    @Test
    void 표준_수집원을_붙이면_CPU_메모리_디스크_네트워크_힙_GC_스레드_지표가_나온다() throws Exception {
        try (MonitorAgent agent = StandardSources.agent(Clock.systemUTC(), mount -> true, List.of(), 10, 10)) {
            agent.sampleOnce();
            Thread.sleep(300);
            MetricSnapshot snap = agent.sampleOnce();

            List<String> metrics = snap.readings().stream().map(Reading::metric).distinct().toList();
            assertThat(snap.failedSources()).isEmpty();
            assertThat(metrics).contains(MetricNames.CPU_SYSTEM, MetricNames.MEMORY_USED, MetricNames.MEMORY_USED_GB,
                    MetricNames.DISK_USED, MetricNames.DISK_FREE_GB, MetricNames.NET_RX, MetricNames.NET_TX,
                    MetricNames.JVM_HEAP_USED, MetricNames.JVM_HEAP_USED_MB, MetricNames.JVM_GC_TIME,
                    MetricNames.JVM_GC_COUNT, MetricNames.JVM_THREADS);
            for (Reading r : snap.readings()) {
                if (r.unit().equals("%")) {
                    assertThat(r.value()).as(r.name()).isBetween(0.0, 100.0);
                } else {
                    assertThat(r.value()).as(r.name()).isGreaterThanOrEqualTo(0.0);
                }
            }
            assertThat(snap.levels()).containsKeys(MetricNames.CPU_SYSTEM, MetricNames.JVM_THREADS);
        }
    }

    @Test
    void 임계를_낮춰_초과를_주입하면_주기_수집에서_이벤트가_난다() throws Exception {
        // 스레드 수는 1 이상이므로 경고 0.5·위험 1이면 첫 표본부터 CRITICAL이다
        List<Threshold> injected = List.of(new Threshold(MetricNames.JVM_THREADS, Direction.ABOVE, 0.5, 1, 1));
        try (MonitorAgent agent = StandardSources.agent(Clock.systemUTC(), mount -> true, injected, 10, 10)) {
            List<ThresholdEvent> heard = new ArrayList<>();
            CountDownLatch fired = new CountDownLatch(1);
            agent.addListener((snapshot, events) -> {
                synchronized (heard) {
                    heard.addAll(events);
                }
                if (!events.isEmpty()) {
                    fired.countDown();
                }
            });
            agent.start(Duration.ofMillis(100));
            assertThat(fired.await(10, TimeUnit.SECONDS)).isTrue();
            synchronized (heard) {
                assertThat(heard).anySatisfy(e -> {
                    assertThat(e.reading()).isEqualTo(MetricNames.JVM_THREADS);
                    assertThat(e.to()).isEqualTo(Level.CRITICAL);
                });
            }
        }
    }

    @Test
    void 덮어쓴_임계가_기본을_대신하고_새_지표는_더한다() {
        List<Threshold> merged = StandardSources.merge(StandardSources.defaultThresholds(), List.of(
                new Threshold(MetricNames.CPU_SYSTEM, Direction.ABOVE, 70, 80, 1),
                new Threshold(MetricNames.NET_RX, Direction.ABOVE, 1e8, 2e8, 2)));
        assertThat(merged).filteredOn(t -> t.metric().equals(MetricNames.CPU_SYSTEM)).singleElement()
                .extracting(Threshold::warn).isEqualTo(70.0);
        assertThat(merged).extracting(Threshold::metric).contains(MetricNames.NET_RX, MetricNames.JVM_THREADS);
        assertThat(merged).hasSize(StandardSources.defaultThresholds().size() + 1);
    }
}
