package com.autotrade.monitorsources;

import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.Reading;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
    private final Supplier<SystemInfo> oshi;
    private final Predicate<String> diskFilter;
    private HardwareAbstractionLayer hal;
    private oshi.software.os.FileSystem fileSystem;
    private long[] previousTicks;
    private long previousRx = -1;
    private long previousTx = -1;
    private long previousNetMillis;

    /**
     * @param diskFilter 지표를 낼 마운트(Windows는 {@code C:\} 같은 드라이브)를 고른다. 로컬 드라이브만 본다
     */
    public SystemSource(Clock clock, Predicate<String> diskFilter) {
        this(clock, diskFilter, SystemInfo::new);
    }

    /**
     * OSHI는 첫 {@link #read()}에서 만든다. 네이티브 적재(JNA)가 실패해도 이 수집원을 붙인 프로세스의 기동은 막지 않고, 그 주기의
     * 이 수집원만 실패로 남는다(architect PR #114 R3).
     */
    SystemSource(Clock clock, Predicate<String> diskFilter, Supplier<SystemInfo> oshi) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diskFilter = Objects.requireNonNull(diskFilter, "diskFilter");
        this.oshi = Objects.requireNonNull(oshi, "oshi");
    }

    @Override
    public String id() {
        return "system";
    }

    /**
     * {@inheritDoc}
     *
     * <p>OSHI 초기화·네이티브 호출이 {@link LinkageError}(예: {@code UnsatisfiedLinkError}, {@code NoClassDefFoundError})를 내면
     * {@link IllegalStateException}으로 감싸 던진다. 에이전트는 그 주기의 이 수집원만 실패로 남기고 다음 주기에 다시 부른다
     * ({@link MetricSource} 계약). 메모리 부족 같은 {@code VirtualMachineError}는 감싸지 않는다.
     */
    @Override
    public List<Reading> read() {
        try {
            if (hal == null) {
                SystemInfo info = oshi.get();
                fileSystem = info.getOperatingSystem().getFileSystem();
                hal = info.getHardware();
            }
            return readAll();
        } catch (LinkageError e) {
            throw new IllegalStateException("OSHI 네이티브 호출 실패: " + e.getClass().getSimpleName(), e);
        }
    }

    private List<Reading> readAll() {
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
