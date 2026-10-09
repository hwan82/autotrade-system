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

dependencies {
    testImplementation(libs.archunit.junit5)
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
    inputs.property("autotrade.projectLayout", projectLayout)
    doFirst {
        systemProperty("autotrade.projectLayout", projectLayout.get())
    }
}
