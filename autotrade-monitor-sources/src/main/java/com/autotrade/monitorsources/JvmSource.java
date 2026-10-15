package com.autotrade.monitorsources;

import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.Reading;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * 이 JVM의 지표: 힙, GC 시간 비율·횟수, 스레드 수, 프로세스 CPU. 에이전트를 붙인 프로세스 자신만 본다(다른 프로세스의 JVM은
 * 그 프로세스에 에이전트를 붙여야 한다). GC 비율은 JVM 가동 시간 차이로 나누므로 시계 주입이 필요 없다.
 */
public final class JvmSource implements MetricSource {

    static final double MIB = 1024.0 * 1024;

    private long previousGcMillis = -1;
    private long previousGcCount = -1;
    private long previousUptime;

    @Override
    public String id() {
        return "jvm";
    }

    @Override
    public List<Reading> read() {
        List<Reading> out = new ArrayList<>();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long limit = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        if (limit > 0) {
            out.add(new Reading(MetricNames.JVM_HEAP_USED, heap.getUsed() * 100.0 / limit, "%"));
        }
        out.add(new Reading(MetricNames.JVM_HEAP_USED_MB, heap.getUsed() / MIB, "MB"));

        long gcMillis = 0;
        long gcCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            // 값이 없으면 -1이다
            gcMillis += Math.max(0, gc.getCollectionTime());
            gcCount += Math.max(0, gc.getCollectionCount());
        }
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        long elapsed = uptime - previousUptime;
        if (previousGcMillis >= 0 && elapsed > 0 && gcMillis >= previousGcMillis && gcCount >= previousGcCount) {
            out.add(new Reading(MetricNames.JVM_GC_TIME, Math.min(100.0, (gcMillis - previousGcMillis) * 100.0 / elapsed), "%"));
            out.add(new Reading(MetricNames.JVM_GC_COUNT, gcCount - previousGcCount, "회"));
        }
        previousGcMillis = gcMillis;
        previousGcCount = gcCount;
        previousUptime = uptime;

        out.add(new Reading(MetricNames.JVM_THREADS, ManagementFactory.getThreadMXBean().getThreadCount(), "개"));
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad();
            if (load >= 0 && load <= 1) {
                out.add(new Reading(MetricNames.JVM_CPU, load * 100, "%"));
            }
        }
        return out;
    }
}
