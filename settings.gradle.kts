pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "autotrade-system"

// 모듈 구성과 의존 방향은 docs/adr/ADR-001-module-structure.md 참고
include(
    "autotrade-domain",
    "autotrade-marketdata",
    "autotrade-strategy",
    "autotrade-selection",
    "autotrade-risk",
    "autotrade-sim",
    "autotrade-broker-api",
    "autotrade-broker-kiwoom",
    "autotrade-broker-kis",
    "autotrade-broker-toss",
    "autotrade-config",
    "autotrade-control",
    "autotrade-app-engine",
    "autotrade-app-gateway",
    "autotrade-app-lab",
    "autotrade-app-watchdog",
    "autotrade-app-collector",
    "autotrade-architecture-test",
    "autotrade-ui",
    "autotrade-notification-jar",
    "autotrade-monitor-agent-jar",
)
