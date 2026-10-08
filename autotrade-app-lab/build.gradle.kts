plugins {
    id("autotrade.java-conventions")
    alias(libs.plugins.spring.boot)
}

// 시뮬레이션 실험실 프로세스(D-013 ③). 낮은 우선순위로 돌고 자원 부족 시 가장 먼저 멈춘다.
// UI 웹 서버(D-020, 127.0.0.1)는 이 프로세스에 둔다(실전 엔진에 두지 않음, UI-01). 웹 의존은 5단계에서 추가한다.
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":autotrade-domain"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-strategy"))
    implementation(project(":autotrade-risk"))
    implementation(project(":autotrade-sim"))
    implementation(project(":autotrade-config"))
    implementation(project(":autotrade-notification-jar"))
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
