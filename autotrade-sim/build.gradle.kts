plugins {
    id("autotrade.pure-java-conventions")
}

dependencies {
    api(project(":autotrade-domain"))
    api(project(":autotrade-strategy"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-risk"))
}

// 골든 스냅샷 갱신: ./gradlew :autotrade-sim:test -Dgolden.update=true (갱신 후 diff를 손 계산과 대조해 커밋)
tasks.test {
    systemProperty("golden.update", System.getProperty("golden.update", "false"))
}
