package com.autotrade.monitorsources;

import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.Reading;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;

/**
 * PC 전체 지표(OSHI): CPU, 메모리, 드라이브별 사용률·여유, 네트워크 합계 속도. 변화율(CPU, 네트워크)은 직전 호출과의 차이로
 * 계산하므로 첫 호출에는 빠진다. 드라이브는 로컬이고 읽기 전용이 아닌 것만 본다. 한 스레드에서만 부른다({@link MetricSource}).
 */
public final class SystemSource implements MetricSource {

    static final double GIB = 1024.0 * 1024 * 1024;

    private final Clock clock;
    private final HardwareAbstractionLayer hal;
    private final oshi.software.os.FileSystem fileSystem;
    private final Predicate<String> diskFilter;
    private long[] previousTicks;
    private long previousRx = -1;
    private long previousTx = -1;
    private long previousNetMillis;

    /**
     * @param diskFilter 지표를 낼 마운트(Windows는 {@code C:\} 같은 드라이브)를 고른다. 로컬 드라이브만 본다
     */
    public SystemSource(Clock clock, Predicate<String> diskFilter) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diskFilter = Objects.requireNonNull(diskFilter, "diskFilter");
        SystemInfo info = new SystemInfo();
        this.hal = info.getHardware();
        this.fileSystem = info.getOperatingSystem().getFileSystem();
    }

    @Override
    public String id() {
        return "system";
    }

    @Override
    public List<Reading> read() {
        List<Reading> out = new ArrayList<>();
        CentralProcessor cpu = hal.getProcessor();
        long[] ticks = cpu.getSystemCpuLoadTicks();
        if (previousTicks != null) {
            double load = cpu.getSystemCpuLoadBetweenTicks(previousTicks);
            if (load >= 0 && load <= 1) {
                out.add(new Reading(MetricNames.CPU_SYSTEM, load * 100, "%"));
            }
        }
        previousTicks = ticks;

        GlobalMemory memory = hal.getMemory();
        long total = memory.getTotal();
        if (total > 0) {
            long used = total - memory.getAvailable();
            out.add(new Reading(MetricNames.MEMORY_USED, used * 100.0 / total, "%"));
            out.add(new Reading(MetricNames.MEMORY_USED_GB, used / GIB, "GB"));
        }

        for (OSFileStore store : fileSystem.getFileStores(true)) {
            String mount = store.getMount();
            long size = store.getTotalSpace();
            if (mount == null || mount.isBlank() || size <= 0 || readOnly(store) || !diskFilter.test(mount)) {
                continue;
            }
            long usable = store.getUsableSpace();
            out.add(new Reading(MetricNames.DISK_USED + ":" + mount, (size - usable) * 100.0 / size, "%"));
            out.add(new Reading(MetricNames.DISK_FREE_GB + ":" + mount, usable / GIB, "GB"));
        }

        readNetwork(out);
        return out;
    }

    private void readNetwork(List<Reading> out) {
        long rx = 0;
        long tx = 0;
        for (NetworkIF nif : hal.getNetworkIFs(false)) {
            if (isLoopback(nif)) {
                continue;
            }
            rx += nif.getBytesRecv();
            tx += nif.getBytesSent();
        }
        long now = clock.millis();
        long elapsed = now - previousNetMillis;
        // 첫 호출, 시계 역행, 카운터 초기화(인터페이스가 빠지거나 재시작)는 속도를 내지 않는다
        if (previousRx >= 0 && elapsed > 0 && rx >= previousRx && tx >= previousTx) {
            out.add(new Reading(MetricNames.NET_RX, (rx - previousRx) * 1000.0 / elapsed, "B/s"));
            out.add(new Reading(MetricNames.NET_TX, (tx - previousTx) * 1000.0 / elapsed, "B/s"));
        }
        previousRx = rx;
        previousTx = tx;
        previousNetMillis = now;
    }

    /** 읽기 전용 마운트(설치 이미지 등)는 채울 수 없으므로 여유 경고 대상이 아니다. */
    static boolean readOnly(OSFileStore store) {
        String options = store.getOptions();
        return options != null && java.util.Arrays.asList(options.split(",")).contains("ro");
    }

    private static boolean isLoopback(NetworkIF nif) {
        try {
            return nif.queryNetworkInterface().isLoopback();
        } catch (java.net.SocketException | RuntimeException e) {
            return false;
        }
    }
}
