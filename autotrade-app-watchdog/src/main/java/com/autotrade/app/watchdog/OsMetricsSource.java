package com.autotrade.app.watchdog;

import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.Reading;
import java.util.ArrayList;
import java.util.List;

/**
 * PC 전체 CPU·메모리 사용률(%) 수집원(D-095). 이름은 실험실 표준 수집원(D-090 {@code MetricNames})과 같은 {@code cpu.system}·
 * {@code memory.used}다. 값을 읽지 못한 주기에는 그 값만 빠지고, 감시는 그 주기를 "판단 못 함"으로 보고 정지 요청을 풀지 않는다.
 */
final class OsMetricsSource implements MetricSource {

    static final String CPU = "cpu.system";
    static final String MEMORY = "memory.used";

    private final OsReadings os;

    OsMetricsSource(OsReadings os) {
        this.os = os;
    }

    @Override
    public String id() {
        return "os";
    }

    @Override
    public List<Reading> read() {
        List<Reading> out = new ArrayList<>();
        double cpu = os.cpuLoad();
        // 음수는 "아직 모름"(첫 호출 등), 1 초과는 잘못 읽은 값이다
        if (cpu >= 0 && cpu <= 1) {
            out.add(new Reading(CPU, cpu * 100, "%"));
        }
        long total = os.totalMemoryBytes();
        long free = os.freeMemoryBytes();
        if (total > 0 && free >= 0 && free <= total) {
            out.add(new Reading(MEMORY, (total - free) * 100.0 / total, "%"));
        }
        return out;
    }
}
