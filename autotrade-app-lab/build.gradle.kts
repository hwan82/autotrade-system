plugins {
    id("autotrade.java-conventions")
    alias(libs.plugins.spring.boot)
}

// 시뮬레이션 실험실 프로세스(D-013 ③). 낮은 우선순위로 돌고 자원 부족 시 가장 먼저 멈춘다.
// UI 웹 서버(D-020, 127.0.0.1)는 이 프로세스에 둔다(실전 엔진에 두지 않음, UI-01, ADR-005).
dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":autotrade-domain"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-strategy"))
    implementation(project(":autotrade-risk"))
    implementation(project(":autotrade-selection"))
    implementation(project(":autotrade-sim"))
    implementation(project(":autotrade-config"))
    implementation(project(":autotrade-control"))
    implementation(project(":autotrade-notification-jar"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    runtimeOnly(project(":autotrade-ui"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly(project(":autotrade-ui"))
}

// bootRun·bootTestRun은 저장소 루트에서 돌려 strategies/ 기본 경로가 저장소 폴더를 가리키게 한다
// 실시간 모의 세션 보고서(live-detail.txt)에 남길 커밋과 dirty 여부(D-076). 리플레이 확인이 code 줄을 따로 적는다
val labRepoRoot = rootProject.projectDir
val labGitCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    workingDir = labRepoRoot
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
val labGitDirty = providers.exec {
    commandLine("git", "status", "--porcelain", "--", ".", ":!experiments")
    workingDir = labRepoRoot
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank().toString() }
tasks.named<JavaExec>("bootRun") {
    workingDir = rootProject.projectDir
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dautotrade.code-commit=${labGitCommit.get()}", "-Dautotrade.code-dirty=${labGitDirty.get()}")
    })
}
tasks.named<JavaExec>("bootTestRun") {
    workingDir = rootProject.projectDir
}
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootTestRun") {
    mainClass.set("com.autotrade.app.lab.DemoLabApplication")
}
