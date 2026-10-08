plugins {
    id("autotrade.pure-java-conventions")
}

// 설정 체계(default → runtime-config.json → UI). 4개 프로세스가 같이 쓰므로 Spring 없이 Jackson만 쓴다.
dependencies {
    api(project(":autotrade-domain"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}
