// 프로세스 진입점 모듈(engine, gateway, watchdog) 공통: 실행 가능한 배포본과 격리 검사(ARC-03, D-013).
plugins {
    id("autotrade.java-conventions")
    application
}

val isolation = extensions.create<ProcessIsolationExtension>("processIsolation")

val verifyProcessIsolation by tasks.registering {
    group = "verification"
    description = "프로세스 런타임 클래스패스에 금지된 모듈·라이브러리가 없는지 검사한다."
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val moduleName = project.name
    val forbiddenProjects = isolation.forbiddenProjects
    val forbiddenGroups = isolation.forbiddenGroups
    inputs.files(runtimeClasspath)
    doLast {
        val components = runtimeClasspath.get().incoming.resolutionResult.allComponents
        val projectViolations = components
            .mapNotNull { (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath }
            .filter { it in forbiddenProjects.get() }
        val groupViolations = components
            .mapNotNull { it.moduleVersion }
            .filter { mv -> forbiddenGroups.get().any { mv.group == it || mv.group.startsWith("$it.") } }
            .map { "${it.group}:${it.name}" }
        val all = projectViolations + groupViolations
        if (all.isNotEmpty()) {
            throw GradleException("$moduleName 프로세스에 들어오면 안 되는 의존이 있습니다: $all")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyProcessIsolation)
}
