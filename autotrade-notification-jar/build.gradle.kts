plugins {
    id("autotrade.pure-java-conventions")
}

// 독립 라이브러리: 알림(MON-06, D-097). 자동매매 도메인을 몰라야 한다(ArchitectureTest).
// 실전 엔진·감시 런타임에 들어가므로 JDK 허용 목록(D-086) 안 타입과 이미 허용된 Jackson만 쓴다. HTTP는 JDK java.net.http.
dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
