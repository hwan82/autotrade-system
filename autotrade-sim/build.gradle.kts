plugins {
    id("autotrade.pure-java-conventions")
}

dependencies {
    api(project(":autotrade-domain"))
    api(project(":autotrade-strategy"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-risk"))
}
