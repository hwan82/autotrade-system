plugins {
    id("autotrade.process-conventions")
}

// 시세 게이트웨이 프로세스(D-013 ②). 주문 API를 호출하지 않는다(MD-01). 주문 포트 타입 참조는 ArchitectureTest가 막는다.
application {
    mainClass.set("com.autotrade.app.gateway.GatewayMain")
    applicationName = "autotrade-gateway"
}

processIsolation {
    forbiddenProjects.set(listOf(":autotrade-sim", ":autotrade-app-lab", ":autotrade-ui", ":autotrade-risk", ":autotrade-strategy", ":autotrade-control"))
    forbiddenGroups.set(listOf("org.springframework"))
}

dependencies {
    implementation(project(":autotrade-domain"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-broker-api"))
    implementation(project(":autotrade-config"))
}
