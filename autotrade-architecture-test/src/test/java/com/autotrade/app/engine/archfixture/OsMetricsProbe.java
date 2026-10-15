package com.autotrade.app.engine.archfixture;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * 규칙 검증용 코드(D-095): PC CPU·메모리 읽기 허용 목록. 시험이 이 클래스를 허용 클래스로 둘 때 {@link #allowed()}만 통과하고
 * 나머지 관리 API(MBean 서버, 다른 MXBean, 허용 밖 읽기 메서드)는 걸려야 한다. 허용 클래스가 아니면 모두 걸린다. 운영 코드가 아니다.
 */
public final class OsMetricsProbe {

    private OsMetricsProbe() {}

    public static double allowed() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean s) {
            return s.getCpuLoad() + s.getTotalMemorySize() + s.getFreeMemorySize();
        }
        return -1;
    }

    public static Object mbeanServer() {
        return ManagementFactory.getPlatformMBeanServer();
    }

    public static Object runtimeBean() {
        return ManagementFactory.getRuntimeMXBean();
    }

    public static long processCpuTime() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    public static String arch() {
        return ManagementFactory.getOperatingSystemMXBean().getArch();
    }
}
