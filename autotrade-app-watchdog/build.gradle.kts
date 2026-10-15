plugins {
    id("autotrade.process-conventions")
}

// 감시·킬스위치 프로세스(D-013 ④). 가장 작고 우선순위가 가장 높다. 1차(D-092): 하트비트·드라이브 여유·정지 요청 파일.
// 긴급 정지·청산 명령 경로, CPU·메모리(Q-053), 자동 재기동은 7단계 risk-ops 후속.
application {
    mainClass.set("com.autotrade.app.watchdog.WatchdogMain")
    applicationName = "autotrade-watchdog"
}

processIsolation {
    forbiddenProjects.set(listOf(":autotrade-sim", ":autotrade-app-lab", ":autotrade-ui", ":autotrade-strategy", ":autotrade-control", ":autotrade-app-collector", ":autotrade-app-experiment", ":autotrade-selection"))
    forbiddenGroups.set(listOf("org.springframework"))
}

dependencies {
    implementation(project(":autotrade-config"))
    implementation(project(":autotrade-monitor-agent-jar"))
    implementation(project(":autotrade-notification-jar"))
}
