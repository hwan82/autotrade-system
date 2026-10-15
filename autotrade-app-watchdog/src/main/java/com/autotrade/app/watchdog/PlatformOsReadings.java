package com.autotrade.app.watchdog;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Optional;

/**
 * JDK 운영체제 MXBean으로 PC 전체 CPU·메모리를 읽는다(D-095, Q-053 사용자 결정 ①). 실전 경로 허용 목록
 * ({@code RealPathReflectionTest.ALLOWED_OS_METRIC_READERS})이 JDK 관리 API를 여는 유일한 클래스이고, 쓸 수 있는 것은
 * {@code ManagementFactory#getOperatingSystemMXBean}과 {@code com.sun.management.OperatingSystemMXBean}의 세 읽기 메서드뿐이다.
 * 외부 jar·네이티브 적재·MBean 서버는 쓰지 않는다. 이 클래스에 다른 일을 더하지 않는다.
 */
final class PlatformOsReadings implements OsReadings {

    private final com.sun.management.OperatingSystemMXBean bean;

    private PlatformOsReadings(com.sun.management.OperatingSystemMXBean bean) {
        this.bean = bean;
    }

    /** 이 JVM이 PC 전체 CPU·메모리를 줄 수 있으면 읽기 객체, 아니면 빈 값(HotSpot 계열 JDK가 아님). */
    static Optional<OsReadings> create() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            return Optional.of(new PlatformOsReadings(sun));
        }
        return Optional.empty();
    }

    @Override
    public double cpuLoad() {
        return bean.getCpuLoad();
    }

    @Override
    public long totalMemoryBytes() {
        return bean.getTotalMemorySize();
    }

    @Override
    public long freeMemoryBytes() {
        return bean.getFreeMemorySize();
    }
}
