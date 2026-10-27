plugins {
    id("autotrade.pure-java-conventions")
}

// 증권사 플러그인(kis). 다른 플러그인을 참조하지 않는다(ArchUnit). 주문·계좌 조회 코드는 order 패키지에만 있고(D-145) 플러그인 밖에서 쓰지 못한다(ArchUnit). 아직 어느 프로세스에도 연결하지 않았다.
dependencies {
    api(project(":autotrade-broker-api"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
