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

// 실험 실행 명령은 autotrade-app-experiment로 옮겼다(D-063): 장 시간 보호와 감시의 정지 요청 파일을 확인하는 진입점이
// 프로세스 모듈에만 있을 수 있어서다(순수 모듈은 벽시계를 못 읽음). 예전 이름은 그쪽으로 넘긴다.
tasks.register("runStrategyStudy") {
    group = "application"
    description = "옮김(D-063): :autotrade-app-experiment:runStrategyStudy"
    dependsOn(":autotrade-app-experiment:runStrategyStudy")
}
tasks.register("runCombinationExperiment") {
    group = "application"
    description = "옮김(D-063): :autotrade-app-experiment:runCombinationExperiment"
    dependsOn(":autotrade-app-experiment:runCombinationExperiment")
}
