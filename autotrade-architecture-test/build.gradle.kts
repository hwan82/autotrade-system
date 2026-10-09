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
// 클래스패스와 따로, settings의 Java 프로젝트 경로와 폴더(projectDir) 대응을 시스템 속성 autotrade.projectDirs로
// 테스트에 넘긴다(자기 자신 포함, "경로=폴더"를 "|"로 잇는다). ModuleOwnershipTest는 클래스 위치를 폴더 이름이 아니라
// 이 표로 프로젝트에 매핑하고(독립 QA PR #21 Q-2: 같은 이름 중첩 프로젝트 구분), 이 표와 클래스패스·OWNERS가 다르면
// 실패한다(손 목록으로 되돌리는 회귀도 잡는다).
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
    // 실행 시점에 계산한다(모든 프로젝트 구성이 끝난 뒤라 플러그인 적용 여부가 확정돼 있다)
    val projectDirs = provider {
        rootProject.allprojects
            .filter { it.pluginManager.hasPlugin("java") }
            .sortedBy { it.path }
            .joinToString("|") { it.path + "=" + it.projectDir.absolutePath }
    }
    inputs.property("autotrade.projectDirs", projectDirs)
    doFirst {
        systemProperty("autotrade.projectDirs", projectDirs.get())
    }
}
