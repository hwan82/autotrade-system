package com.autotrade.app.watchdog;

/**
 * PC 전체 CPU·메모리 값(D-095). 운영은 {@link PlatformOsReadings}, 시험은 값을 넣는 가짜를 쓴다. JDK 관리 타입은 이 인터페이스에
 * 드러나지 않는다(실전 경로 허용 목록은 그 타입을 플랫폼 읽기 클래스 하나에만 연다).
 */
interface OsReadings {

    /** 최근 PC 전체 CPU 사용률(0~1). 아직 모르거나 읽지 못하면 음수. */
    double cpuLoad();

    /** 전체 물리 메모리 바이트. 읽지 못하면 0 이하. */
    long totalMemoryBytes();

    /** 비어 있는 물리 메모리 바이트. 읽지 못하면 음수. */
    long freeMemoryBytes();
}
