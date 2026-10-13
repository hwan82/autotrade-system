plugins {
    id("autotrade.process-conventions")
}

// 실험 실행 명령(D-063, Q-049). D-013의 상주 프로세스 4개에 들지 않는 단발 명령이다(실행 뒤 종료).
// autotrade-sim의 실험 본체(조합 실험 CombinationMain, 합성 시세 비교 StudyMain)에 장 시간 보호와 감시의 정지 요청 파일
// (broker-and-fault-design 5.2 0번)을 RunGate로 넣는다. 순수 모듈은 벽시계를 읽지 못하므로(ArchUnit NO_SYSTEM_TIME)
// 시스템 시계는 여기 진입점(ExperimentMain)만 만든다. 증권사 API·실시간 시세·주문·실험실(Spring)을 쓰지 않는다.
application {
    mainClass.set("com.autotrade.app.experiment.ExperimentMain")
    applicationName = "autotrade-experiment"
}

processIsolation {
    forbiddenProjects.set(listOf(
        ":autotrade-app-lab", ":autotrade-ui", ":autotrade-control", ":autotrade-broker-api", ":autotrade-broker-kiwoom",
        ":autotrade-broker-kis", ":autotrade-broker-toss", ":autotrade-app-engine", ":autotrade-app-gateway",
        ":autotrade-app-watchdog", ":autotrade-app-collector"))
    forbiddenGroups.set(listOf("org.springframework"))
}

dependencies {
    implementation(project(":autotrade-domain"))
    implementation(project(":autotrade-marketdata"))
    implementation(project(":autotrade-sim"))
    implementation(project(":autotrade-config"))
}

// 실험 결과에 남길 커밋과 dirty 여부(experiments/ 밖의 변경이 있으면 dirty). 코드를 먼저 커밋하고 돌린다.
val repoRoot = rootProject.projectDir
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    workingDir = repoRoot
}.standardOutput.asText.map { it.trim() }
val gitDirty = providers.exec {
    commandLine("git", "status", "--porcelain", "--", ".", ":!experiments")
    workingDir = repoRoot
}.standardOutput.asText.map { it.isNotBlank().toString() }
// 실전 엔진을 끈 날에만: -PallowDuringSession (장 시간 보호만 풀린다. 정지 요청 파일은 풀리지 않는다).
// 값은 그대로 ExperimentMain에 넘겨 거기서 판단한다: 값 없음·true만 해제, 그 밖의 값(false 포함)은 입력 오류(PR #52 QA F2)
val allowDuringSession = providers.gradleProperty("allowDuringSession")

// 조합 실험(D-061): ./gradlew :autotrade-app-experiment:runCombinationExperiment -Pexperiment=<실험ID>
//     [-PsnapshotsDir=폴더] [-PverifyDeterminism] [-PallowDuringSession] [-Pheap=6g]
// experiments/<실험ID>/experiment.yaml(등록 파일)을 읽어 수집 스냅샷(일봉·재무) 위에서 선정 × 지표 조합을 돌리고 결과를
// 같은 폴더에 쓴다. 절차는 docs/strategy/combination-experiments.md, 커밋 규칙은 experiments/README.md.
val runCombinationExperiment by tasks.registering(JavaExec::class) {
    group = "application"
    description = "선정 × 매매지표 조합 실험을 수집 스냅샷 위에서 돌려 experiments/<실험ID>/에 결과를 쓴다(장 시간 보호·정지 요청 확인)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.autotrade.app.experiment.ExperimentMain")
    maxHeapSize = (findProperty("heap") as String?) ?: "6g"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    val experiment = providers.gradleProperty("experiment")
    val snapshotsDir = providers.gradleProperty("snapshotsDir")
    val verify = providers.gradleProperty("verifyDeterminism")
    argumentProviders.add(CommandLineArgumentProvider {
        val id = experiment.orNull ?: throw GradleException("-Pexperiment=<실험ID>가 필요합니다")
        buildList {
            add("combination")
            allowDuringSession.orNull?.let { add("--allow-during-session=$it") }
            addAll(listOf(repoRoot.absolutePath, gitCommit.get(), gitDirty.get(), id))
            snapshotsDir.orNull?.let { addAll(listOf("--snapshots-dir", it)) }
            if (verify.isPresent) add("--verify-determinism")
        }
    })
}

// 녹화 실험(D-069): ./gradlew :autotrade-app-experiment:runRecordingExperiment -Pexperiment=<실험ID>
//     [-PrecordingsDir=폴더] [-PverifyDeterminism] [-PallowDuringSession] [-Pheap=3g]
// experiments/<실험ID>/experiment.yaml(input: recording)을 읽어 녹화 폴더(기본 <설정 폴더>/recordings)의 실녹화 위에서 장중
// 프로필을 돌리고 결과를 같은 폴더에 쓴다. 녹화 파일은 결과 폴더에 쓰지 않는다(재배포 금지).
val runRecordingExperiment by tasks.registering(JavaExec::class) {
    group = "application"
    description = "전략 프로필을 실녹화 위에서 돌려 experiments/<실험ID>/에 결과를 쓴다(장 시간 보호·정지 요청 확인)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.autotrade.app.experiment.ExperimentMain")
    maxHeapSize = (findProperty("heap") as String?) ?: "3g"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    val experiment = providers.gradleProperty("experiment")
    val recordingsDir = providers.gradleProperty("recordingsDir")
    val verify = providers.gradleProperty("verifyDeterminism")
    argumentProviders.add(CommandLineArgumentProvider {
        val id = experiment.orNull ?: throw GradleException("-Pexperiment=<실험ID>가 필요합니다")
        buildList {
            add("recording")
            allowDuringSession.orNull?.let { add("--allow-during-session=$it") }
            addAll(listOf(repoRoot.absolutePath, gitCommit.get(), gitDirty.get(), id))
            recordingsDir.orNull?.let { addAll(listOf("--recordings-dir", it)) }
            if (verify.isPresent) add("--verify-determinism")
        }
    })
}

// 4단계 첫 다중 전략 비교 실험(합성 시세): ./gradlew :autotrade-app-experiment:runStrategyStudy [-PallowDuringSession]
// 결과는 experiments/EXP-20261008-STR01/에 쓴다.
val runStrategyStudy by tasks.registering(JavaExec::class) {
    group = "application"
    description = "전략 프로필 비교 실험을 돌려 experiments/<실험ID>/에 결과 표를 쓴다(장 시간 보호·정지 요청 확인)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.autotrade.app.experiment.ExperimentMain")
    maxHeapSize = "4g"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            add("study")
            allowDuringSession.orNull?.let { add("--allow-during-session=$it") }
            addAll(listOf(repoRoot.absolutePath, gitCommit.get(), gitDirty.get()))
        }
    })
}
