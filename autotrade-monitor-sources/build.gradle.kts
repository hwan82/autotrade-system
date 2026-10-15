plugins {
    id("autotrade.java-conventions")
}

// 감시 에이전트 표준 수집원(MON-05, D-090): OS 지표는 OSHI, JVM 지표는 java.lang.management로 읽는다. 자동매매 도메인을 몰라야
// 하고(ArchitectureTest), 실전 엔진·감시 프로세스에는 넣지 않는다(외부 jar 허용 목록 D-086 밖이다). 다른 프로젝트는
// autotrade-monitor-agent-jar와 이 모듈을 의존성에 넣고 StandardSources로 에이전트를 만들면 된다.
dependencies {
    api(project(":autotrade-monitor-agent-jar"))
    implementation(libs.oshi.core)
}
