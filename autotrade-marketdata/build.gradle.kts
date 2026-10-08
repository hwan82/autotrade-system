plugins {
    id("autotrade.pure-java-conventions")
}

// 시세 계약, 공통 WebSocket 소스, 녹화·리플레이, 거래소 캘린더, 일봉 스냅샷(ADR-002).
// 증권사별 메시지 형식은 플러그인 모듈(autotrade-broker-*)에 둔다.
dependencies {
    api(project(":autotrade-domain"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
