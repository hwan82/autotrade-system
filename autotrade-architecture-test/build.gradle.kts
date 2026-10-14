plugins {
    id("autotrade.java-conventions")
}

// 모듈 경계·결정론 규칙(ArchUnit). 모든 모듈을 테스트 클래스패스에 올려 한곳에서 검사한다.
//
// 의존 목록은 손으로 적지 않는다(risk-ops PR #21 검토 R-1). settings.gradle.kts에 include된 Java 프로젝트는
// 중첩 프로젝트까지 자기 자신을 빼고 전부 자동으로 testImplementation이 된다. 그래서 어떤 모듈도 의존하지 않는 새
// 모듈(새 프로세스, 명령 채널 등)도 ArchitectureTest·ModuleOwnershipTest 밖에 놓일 수 없다. 새 모듈은
// ModuleOwnershipTest.OWNERS 표만 고치면 된다(표에 없으면 그 테스트가 실패한다. ADR-002 9장).
//
// 클래스패스와 따로, settings의 Java 프로젝트 경로·폴더(projectDir)·선언된 출력을 시스템 속성 autotrade.projectLayout으로
// 테스트에 넘긴다(자기 자신 포함, 아래 tasks.test). ModuleOwnershipTest는 클래스 위치를 폴더 이름이 아니라 이 표로
// 프로젝트에 매핑하고(독립 QA PR #21 Q-2, risk-ops 재검토 2 R-7), 이 표와 클래스패스·OWNERS가 다르면 실패한다(손 목록으로
// 되돌리는 회귀도 잡는다).
val architectureTest = project
val otherProjects = rootProject.subprojects.filter { it != architectureTest }

// 실전 경로 런타임(D-085, 독립 QA PR #21 Q-4·risk-ops R-8): 실전 엔진·감시 프로세스와, 7단계에 실전 엔진이 불러올 증권사
// 플러그인을 실제로 해석한 런타임 클래스패스. 손 목록이 아니라 Gradle이 해석한 결과에서 (1) 우리 프로젝트 목록(리플렉션 금지
// 규칙의 모듈 범위)과 (2) 외부 라이브러리 목록(허용 목록과 대조)을 뽑아 테스트에 넘긴다. 실전 엔진에 새 모듈이나 외부 jar가
// 들어오면 빌드 파일 수정 없이도 자동으로 범위에 들어오고, 허용 목록에 없는 외부 jar는 RealPathReflectionTest가 실패시킨다.
// 이 구성은 해석에만 쓰고 테스트 클래스패스에는 올리지 않는다.
val realPathProcesses = listOf(":autotrade-app-engine", ":autotrade-app-watchdog")
val realPathRuntime = configurations.create("realPathRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "실전 엔진·감시·증권사 플러그인의 런타임 클래스패스(아키텍처 테스트 범위 계산용)"
}

dependencies {
    testImplementation(libs.archunit.junit5)
    // Jackson 다형 역직렬화 규칙의 위반 고정 예제(archfixture)용
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.databind)
}

// 다른 모듈의 런타임 변형(jar)을 고른다(java 플러그인의 runtimeClasspath와 같은 속성)
realPathRuntime.attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
}

realPathProcesses.forEach { path ->
    architectureTest.dependencies.add(realPathRuntime.name, architectureTest.dependencies.project(mapOf("path" to path)))
}
// 증권사 플러그인(:autotrade-broker-*)은 7단계에 실전 엔진이 불러온다. 지금 엔진 의존에 없어도 범위에 넣는다
otherProjects.filter { it.name.startsWith("autotrade-broker-") }.forEach { module ->
    module.pluginManager.withPlugin("java") {
        architectureTest.dependencies.add(realPathRuntime.name, architectureTest.dependencies.project(mapOf("path" to module.path)))
    }
}

otherProjects.forEach { module ->
    // 다른 모듈이 아직 구성되지 않았을 수 있어(구성 순서는 이름순) 플러그인이 적용되는 시점에 더한다.
    // java 플러그인이 없는 프로젝트(중간 폴더 프로젝트 등)는 클래스를 만들 수 없으므로 검사 대상이 아니다.
    module.pluginManager.withPlugin("java") {
        architectureTest.dependencies.add(
                "testImplementation", architectureTest.dependencies.project(mapOf("path" to module.path)))
    }
}

tasks.test {
    // 실행 시점에 계산한다(모든 프로젝트 구성이 끝난 뒤라 플러그인 적용 여부와 출력 경로가 확정돼 있다).
    // 프로젝트마다 폴더(DIR)와 선언된 운영 출력(MAIN: main 클래스 폴더, main 자원 폴더, jar 산출물)을 넘긴다. 이 모듈은
    // 테스트 출력(TEST)도 넘긴다. BUILT는 클래스·자원·묶음 태스크의 실제 출력으로, 자기 폴더 안인지만 검사한다. 테스트는 클래스패스 항목을 폴더 접두어가 아니라 이 선언 출력과 정확히 맞춰 프로젝트에
    // 매핑한다(risk-ops PR #21 재검토 2 R-7). 형식: 항목은 "|"로, 항목 안 필드(종류, 프로젝트 경로, 파일)는 탭으로 잇는다.
    val projectLayout = provider {
        val items = mutableListOf<String>()
        // 저장소 루트와, 외부 라이브러리가 올 수 있는 곳(Gradle 사용자 홈의 의존성 캐시, Gradle 배포판). 클래스패스 항목은
        // 선언 출력이거나 외부 허용 위치 안이어야 한다(저장소 안 파일 jar 의존이나 임의 경로의 jar는 실패)
        items.add("ROOT\t-\t" + rootProject.rootDir.absolutePath)
        items.add("EXTERNAL\t-\t" + gradle.gradleUserHomeDir.absolutePath)
        gradle.gradleHomeDir?.let { items.add("EXTERNAL\t-\t" + it.absolutePath) }
        rootProject.allprojects
            .filter { it.pluginManager.hasPlugin("java") }
            .sortedBy { it.path }
            .forEach { p ->
                fun add(kind: String, file: File) = items.add(kind + "\t" + p.path + "\t" + file.absolutePath)
                add("DIR", p.projectDir)
                val sourceSets = p.extensions.getByType(SourceSetContainer::class.java)
                val main = sourceSets.getByName("main").output
                main.classesDirs.files.forEach { add("MAIN", it) }
                main.resourcesDir?.let { add("MAIN", it) }
                add("MAIN", p.tasks.named("jar", Jar::class.java).get().archiveFile.get().asFile)
                // 클래스·자원·묶음을 만드는 모든 태스크의 실제 출력(BUILT). 소스 세트 선언과 따로 태스크 출력만 다른 곳으로
                // 돌리는 변형도 "자기 폴더 밖" 검사에 걸리게 한다
                p.tasks.withType(AbstractCompile::class.java).forEach { add("BUILT", it.destinationDirectory.get().asFile) }
                p.tasks.withType(ProcessResources::class.java).forEach { add("BUILT", it.destinationDir) }
                p.tasks.withType(AbstractArchiveTask::class.java).forEach { add("BUILT", it.archiveFile.get().asFile) }
                if (p == architectureTest) {
                    val test = sourceSets.getByName("test").output
                    test.classesDirs.files.forEach { add("TEST", it) }
                    test.resourcesDir?.let { add("TEST", it) }
                }
            }
        items.joinToString("|")
    }
    // 실전 경로 런타임의 구성 요소. 형식: "PROJECT\t:경로", "MODULE\tgroup:name", 그 밖(파일 의존 등)은 "OTHER\t..."를
    // "|"로 잇는다(정렬). 해석 그래프의 구성 요소(플랫폼 포함)와 실제 산출물의 출처를 합친다. 파일 의존(files(...))은 그래프에
    // 없고 산출물로만 보이므로 산출물도 본다
    fun describe(id: org.gradle.api.artifacts.component.ComponentIdentifier): String = when (id) {
        is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> "PROJECT\t" + id.projectPath
        is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> "MODULE\t" + id.group + ":" + id.module
        else -> "OTHER\t" + id.displayName
    }
    val realPathComponents = provider {
        val result = realPathRuntime.incoming.resolutionResult
        // 해석의 뿌리(이 모듈 자신)는 실전 경로 구성 요소가 아니다
        val fromGraph = result.allComponents.filter { it != result.root }.map { describe(it.id) }
        val fromArtifacts = realPathRuntime.incoming.artifacts.artifacts.map { describe(it.id.componentIdentifier) }
        (fromGraph + fromArtifacts).distinct().sorted().joinToString("|")
    }
    inputs.property("autotrade.projectLayout", projectLayout)
    inputs.property("autotrade.realPathComponents", realPathComponents)
    doFirst {
        systemProperty("autotrade.projectLayout", projectLayout.get())
        systemProperty("autotrade.realPathComponents", realPathComponents.get())
    }
}
