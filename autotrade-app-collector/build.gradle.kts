plugins {
    id("autotrade.process-conventions")
}

// 과거 데이터 수집 일괄 명령(ADR-008, D-051). D-013의 상주 프로세스 4개에 들지 않는 단발 명령이다(실행 뒤 종료).
// KRX Open API(일봉·상장주식수)와 OpenDART(재무)만 부른다. 증권사 API·실시간 시세·주문을 쓰지 않고,
// 키는 collector-secrets.json에서만 읽는다(runtime-config.json·secrets.json을 읽지 않는다. ArchitectureTest가 막는다).
application {
    mainClass.set("com.autotrade.app.collector.CollectorMain")
    applicationName = "autotrade-collector"
    // 실전 엔진과 같은 PC에서 돈다: 힙 상한 고정(broker-and-fault-design 5.1, risk-ops 검토 PR #39 F1). 메모리가 모자라면
    // 상한을 올리지 말고 --from으로 기간을 줄인다(runbook 3장)
    applicationDefaultJvmArgs = listOf("-Xmx2g")
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
    maxHeapSize = "2g"
    workingDir = rootProject.projectDir // 품질 리포트 기본 경로(experiments/_data-quality)는 저장소 루트 기준
    // Windows PowerShell(chcp 65001)에서 콘솔 한글이 깨지지 않게 표준 출력을 UTF-8로(실험실 bootRun과 같음)
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
