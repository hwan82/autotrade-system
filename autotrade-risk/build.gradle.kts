plugins {
    id("autotrade.pure-java-conventions")
}

// 리스크 검증·블랙리스트·RealTradingGuard는 가상과 실전이 공유하는 코드다(프로젝트 지침 5장).
// RealTradingGuard 구현은 risk-ops 작업으로 분리한다.
dependencies {
    api(project(":autotrade-domain"))
}
