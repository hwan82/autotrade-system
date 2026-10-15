package com.autotrade.monitorsources;

/** 표준 수집원이 내는 지표 이름. 대상이 있는 지표는 {@code 이름:대상}으로 나온다(예: {@code disk.free-gb:C:\}). */
public final class MetricNames {

    /** PC 전체 CPU 사용률(%). 직전 수집과의 틱 차이라 첫 수집에는 없다. */
    public static final String CPU_SYSTEM = "cpu.system";
    /** PC 메모리 사용률(%), 사용량(GB). */
    public static final String MEMORY_USED = "memory.used";
    public static final String MEMORY_USED_GB = "memory.used-gb";
    /** 드라이브(마운트)별 사용률(%)과 여유(GB, 1GB = 2^30바이트). */
    public static final String DISK_USED = "disk.used";
    public static final String DISK_FREE_GB = "disk.free-gb";
    /** 루프백을 뺀 네트워크 인터페이스 합계 수신·송신(바이트/초). 첫 수집에는 없다. */
    public static final String NET_RX = "net.rx";
    public static final String NET_TX = "net.tx";
    /** 이 JVM 힙 사용률(최대 대비 %, 최대가 없으면 확보량 대비)과 사용량(MB). */
    public static final String JVM_HEAP_USED = "jvm.heap.used";
    public static final String JVM_HEAP_USED_MB = "jvm.heap.used-mb";
    /** 직전 수집 이후 GC에 쓴 시간 비율(%)과 횟수. 첫 수집에는 없다. */
    public static final String JVM_GC_TIME = "jvm.gc-time";
    public static final String JVM_GC_COUNT = "jvm.gc-count";
    /** 이 JVM의 살아 있는 스레드 수. */
    public static final String JVM_THREADS = "jvm.threads";
    /** 이 JVM 프로세스의 CPU 사용률(PC 전체 대비 %). */
    public static final String JVM_CPU = "jvm.cpu";

    private MetricNames() {}
}
