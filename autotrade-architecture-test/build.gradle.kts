plugins {
    id("autotrade.java-conventions")
}

// 모듈 경계·결정론 규칙(ArchUnit). 모든 모듈을 테스트 클래스패스에 올려 한곳에서 검사한다.
//
// 의존 목록은 손으로 적지 않는다(risk-ops PR #21 검토 R-1). settings.gradle.kts에 include된 Java 모듈은 자기 자신을
// 빼고 전부 자동으로 testImplementation이 된다. 그래서 어떤 모듈도 의존하지 않는 새 모듈(새 프로세스, 명령 채널 등)도
// ArchitectureTest·ModuleOwnershipTest 밖에 놓일 수 없다. 새 모듈은 ModuleOwnershipTest.OWNERS 표만 고치면 된다
// (표에 없으면 그 테스트가 실패한다. ADR-002 9장).
//
// 손 목록으로 되돌리는 실수까지 막기 위해, 클래스패스와 따로 settings의 Java 모듈 이름 목록을 테스트에 넘긴다
// (시스템 속성 autotrade.settingsJavaModules). ModuleOwnershipTest는 이 목록과 클래스패스에서 본 모듈이 다르면 실패한다.
val architectureTest = project
val settingsJavaModules = rootProject.subprojects.filter { it != architectureTest }

dependencies {
    testImplementation(libs.archunit.junit5)
}

settingsJavaModules.forEach { module ->
    // 다른 모듈이 아직 구성되지 않았을 수 있어(구성 순서는 이름순) 플러그인이 적용되는 시점에 더한다.
    // java 플러그인이 없는 모듈은 클래스를 만들 수 없으므로 검사 대상이 아니다.
    module.pluginManager.withPlugin("java") {
        architectureTest.dependencies.add("testImplementation", architectureTest.dependencies.project(mapOf("path" to module.path)))
    }
}

tasks.test {
    // 실행 시점에 계산한다(모든 프로젝트 구성이 끝난 뒤라 플러그인 적용 여부가 확정돼 있다)
    val moduleNames = provider {
        settingsJavaModules.filter { it.pluginManager.hasPlugin("java") }.map { it.name }.sorted().joinToString(",")
    }
    inputs.property("autotrade.settingsJavaModules", moduleNames)
    doFirst {
        systemProperty("autotrade.settingsJavaModules", moduleNames.get())
    }
}
