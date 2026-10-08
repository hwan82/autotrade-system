// 순수 Java 모듈(domain, marketdata, strategy, risk, sim, broker-api) 전용.
// Spring, DB 드라이버, 증권사 SDK가 런타임 클래스패스에 들어오면 빌드를 실패시킨다(ADR-001).
plugins {
    id("autotrade.java-conventions")
}

val forbiddenGroups = listOf(
    "org.springframework",
    "org.postgresql",
    "com.zaxxer",            // HikariCP
    "org.flywaydb",
    "org.hibernate",
    "jakarta.persistence",
    "com.h2database",
)

val verifyPureDependencies by tasks.registering {
    group = "verification"
    description = "순수 Java 모듈에 금지 의존(Spring, DB, 증권사 SDK)이 없는지 검사한다."
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val moduleName = project.name
    inputs.files(runtimeClasspath)
    doLast {
        val violations = runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { mv -> forbiddenGroups.any { mv.group == it || mv.group.startsWith("$it.") } }
            .map { "${it.group}:${it.name}:${it.version}" }
        if (violations.isNotEmpty()) {
            throw GradleException("$moduleName 은 순수 Java 모듈인데 금지 의존이 있습니다: $violations")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPureDependencies)
}
