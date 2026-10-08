plugins {
    id("autotrade.java-conventions")
    alias(libs.plugins.spring.boot)
}

// Spring 연결은 이 모듈에만 둔다(ADR-001). 지금은 시뮬레이션 실험실 프로세스의 기동 뼈대다.
// 실전 엔진·시세 게이트웨이·감시 프로세스의 진입점은 각 단계에서 별도로 만든다(D-013).
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":autotrade-domain"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-strategy"))
    implementation(project(":autotrade-risk"))
    implementation(project(":autotrade-sim"))
    implementation(project(":autotrade-broker-api"))
    implementation(project(":autotrade-config"))
    implementation(project(":autotrade-notification-jar"))
    implementation(project(":autotrade-monitor-agent-jar"))
    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.archunit.junit5)
}
