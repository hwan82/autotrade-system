plugins {
    id("autotrade.process-conventions")
}

// 과거 데이터 수집 일괄 명령(ADR-008, D-051). D-013의 상주 프로세스 4개에 들지 않는 단발 명령이다(실행 뒤 종료).
// KRX Open API(일봉·상장주식수)와 OpenDART(재무)만 부른다. 증권사 API·실시간 시세·주문을 쓰지 않고,
// 키는 collector-secrets.json에서만 읽는다(runtime-config.json·secrets.json을 읽지 않는다. ArchitectureTest가 막는다).
application {
    mainClass.set("com.autotrade.app.collector.CollectorMain")
    applicationName = "autotrade-collector"
}

processIsolation {
    forbiddenProjects.set(listOf(
        ":autotrade-sim", ":autotrade-app-lab", ":autotrade-ui", ":autotrade-risk", ":autotrade-strategy",
        ":autotrade-control", ":autotrade-broker-api", ":autotrade-broker-kiwoom", ":autotrade-broker-kis",
        ":autotrade-app-engine", ":autotrade-app-gateway", ":autotrade-app-watchdog"))
    forbiddenGroups.set(listOf("org.springframework"))
}

dependencies {
    implementation(project(":autotrade-domain"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-config"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir // 품질 리포트 기본 경로(experiments/_data-quality)는 저장소 루트 기준
}
