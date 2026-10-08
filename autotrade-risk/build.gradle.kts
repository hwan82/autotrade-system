plugins {
    id("autotrade.pure-java-conventions")
}

// 리스크 검증·블랙리스트·RealTradingGuard는 가상과 실전이 공유하는 코드다(프로젝트 지침 5장).
// RealTradingGuard 골격은 com.autotrade.risk.guard(ADR-002).
dependencies {
    api(project(":autotrade-domain"))
}
