plugins {
    id("autotrade.process-conventions")
}

// 실전 매매 엔진 프로세스(D-013 ①). 다른 프로세스가 죽거나 느려져도 보유 종목 보호를 계속해야 하므로
// 실험실·UI·Spring을 런타임 클래스패스에 두지 않는다(ARC-03). 실전 주문 출구는 RealOrderGateways만 연다(ADR-002).
application {
    mainClass.set("com.autotrade.app.engine.EngineMain")
    applicationName = "autotrade-engine"
}

// OSHI·JNA·java.lang.management를 쓰는 autotrade-monitor-sources는 간접 의존으로도 들어오지 않게 직접 금지한다(PR #114 QA R5, architect A1)
processIsolation {
    forbiddenProjects.set(listOf(":autotrade-sim", ":autotrade-app-lab", ":autotrade-ui", ":autotrade-app-gateway", ":autotrade-app-collector", ":autotrade-app-experiment", ":autotrade-monitor-sources"))
    forbiddenGroups.set(listOf("org.springframework"))
}

dependencies {
    implementation(project(":autotrade-domain"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-strategy"))
    implementation(project(":autotrade-risk"))
    implementation(project(":autotrade-broker-api"))
    implementation(project(":autotrade-config"))
    implementation(project(":autotrade-control"))
    implementation(project(":autotrade-notification-jar"))
    // 하트비트 기록만(MON-02, D-092). JDK 허용 목록 안 코어라 실전 경로 검사(D-086)를 그대로 통과한다
    implementation(project(":autotrade-monitor-agent-jar"))
}
