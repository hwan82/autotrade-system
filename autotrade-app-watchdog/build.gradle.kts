plugins {
    id("autotrade.process-conventions")
}

// 감시·킬스위치 프로세스(D-013 ④). 가장 작고 우선순위가 가장 높다. 1차(D-092): 하트비트·드라이브 여유·정지 요청 파일.
// 알림(D-100): 상태 변화를 알림 큐로 넣는다(HTTP는 알림 전용 스레드). 긴급 정지·청산 명령 경로, 자동 재기동은 7단계 risk-ops 후속.
application {
    mainClass.set("com.autotrade.app.watchdog.WatchdogMain")
    applicationName = "autotrade-watchdog"
}

// OSHI·JNA·java.lang.management를 쓰는 autotrade-monitor-sources는 간접 의존으로도 들어오지 않게 직접 금지한다(PR #114 QA R5, architect A1)
processIsolation {
    forbiddenProjects.set(listOf(":autotrade-sim", ":autotrade-app-lab", ":autotrade-ui", ":autotrade-strategy", ":autotrade-control", ":autotrade-app-collector", ":autotrade-app-experiment", ":autotrade-selection", ":autotrade-monitor-sources"))
    forbiddenGroups.set(listOf("org.springframework", "com.github.oshi", "net.java.dev.jna"))
}

dependencies {
    implementation(project(":autotrade-config"))
    implementation(project(":autotrade-monitor-agent-jar"))
    implementation(project(":autotrade-notification-jar"))
}
