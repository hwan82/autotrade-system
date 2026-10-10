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
    implementation(project(":autotrade-broker-kiwoom"))
    implementation(project(":autotrade-broker-kis"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

// 녹화일 두 번 리플레이 확인(2단계 완료 기준 MD-06). 녹화 파일만 읽고 네트워크·주문을 쓰지 않는다.
// 사용: ./gradlew :autotrade-app-gateway:replayCheck --args="2026-10-12 replay-2026-10-12.md"
tasks.register<JavaExec>("replayCheck") {
    group = "verification"
    description = "녹화일을 두 번 리플레이해 결과가 같은지 보고 종목별 체결 요약을 낸다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.autotrade.app.gateway.ReplayCheck")
    workingDir = rootProject.projectDir // 보고서 상대 경로는 저장소 루트 기준
}

// 종목명 표 만들기: 한국투자 공개 종목 마스터 파일을 받아 <설정 폴더>/symbols/KRX.csv를 만든다(키·주문 API 안 씀).
// 사용: ./gradlew :autotrade-app-gateway:symbolMaster
tasks.register<JavaExec>("symbolMaster") {
    group = "application"
    description = "한국투자 공개 종목 마스터(코스피·코스닥)를 받아 설정 폴더의 symbols/KRX.csv를 만든다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.autotrade.app.gateway.SymbolMasterUpdate")
    workingDir = rootProject.projectDir
}

// 녹화일 원본 대조용 키움 정규장 일봉 표(ka11081 조회 TR만, 주문·계좌 API 안 씀). 게이트웨이를 끈 뒤 실행한다.
// 사용: ./gradlew :autotrade-app-gateway:kiwoomDaily --args="2026-10-12 kiwoom-daily-2026-10-12.md"
tasks.register<JavaExec>("kiwoomDaily") {
    group = "verification"
    description = "키움 REST(ka11081)로 녹화 종목의 KRX 정규장 일봉을 받아 ReplayCheck 대조용 표를 만든다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.autotrade.app.gateway.KiwoomDailyCheck")
    workingDir = rootProject.projectDir
}
