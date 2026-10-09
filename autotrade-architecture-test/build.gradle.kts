plugins {
    id("autotrade.java-conventions")
}

// 모듈 경계·결정론 규칙(ArchUnit). 모든 모듈을 테스트 클래스패스에 올려 한곳에서 검사한다.
// 새 모듈을 만들면 여기에 추가한다(ADR-001 4장).
dependencies {
    testImplementation(libs.archunit.junit5)
    listOf(
        ":autotrade-domain", ":autotrade-marketdata", ":autotrade-strategy", ":autotrade-risk", ":autotrade-sim",
        ":autotrade-broker-api", ":autotrade-config", ":autotrade-control", ":autotrade-notification-jar", ":autotrade-monitor-agent-jar",
        ":autotrade-app-engine", ":autotrade-app-gateway", ":autotrade-app-lab", ":autotrade-app-watchdog",
    ).forEach { testImplementation(project(it)) }
}
