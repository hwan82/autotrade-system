plugins {
    id("autotrade.pure-java-conventions")
}

dependencies {
    api(project(":autotrade-domain"))
    api(project(":autotrade-strategy"))
    api(project(":autotrade-selection"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-risk"))
}

// 골든 스냅샷 갱신: ./gradlew :autotrade-sim:test -Dgolden.update=true (갱신 후 diff를 손 계산과 대조해 커밋)
tasks.test {
    systemProperty("golden.update", System.getProperty("golden.update", "false"))
}

// 4단계 첫 다중 전략 비교 실험(합성 시세): ./gradlew :autotrade-sim:runStrategyStudy
// 결과는 experiments/EXP-20261008-STR01/에 쓴다. 코드를 먼저 커밋하고 돌려야 결과에 깨끗한 커밋이 남는다
// (experiments/ 밖의 변경이 있으면 dirty로 기록).
val runStrategyStudy by tasks.registering(JavaExec::class) {
    group = "application"
    description = "전략 프로필 비교 실험을 돌려 experiments/<실험ID>/에 결과 표를 쓴다."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.autotrade.sim.study.StudyMain")
    maxHeapSize = "4g"
    jvmArgs("-Dstdout.encoding=UTF-8")
    val root = rootProject.projectDir
    val commit = providers.exec {
        commandLine("git", "rev-parse", "HEAD")
        workingDir = root
    }.standardOutput.asText.map { it.trim() }
    val dirty = providers.exec {
        commandLine("git", "status", "--porcelain", "--", ".", ":!experiments")
        workingDir = root
    }.standardOutput.asText.map { it.isNotBlank().toString() }
    argumentProviders.add(CommandLineArgumentProvider { listOf(root.absolutePath, commit.get(), dirty.get()) })
}
