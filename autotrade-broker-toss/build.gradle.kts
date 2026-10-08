plugins {
    id("autotrade.pure-java-conventions")
}

// 증권사 플러그인(toss). 다른 플러그인을 참조하지 않는다(ArchUnit). 2단계는 시세만 있고 주문 코드는 없다(MD-01).
dependencies {
    api(project(":autotrade-broker-api"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
