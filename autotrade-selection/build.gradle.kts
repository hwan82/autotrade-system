plugins {
    id("autotrade.pure-java-conventions")
}

// 선정 프로필·조합·선정 결과 계약(ADR-007 2.1). 실험실, 선정 결과 생산 일괄 명령, 실전 엔진이 같은 코드를 쓴다.
// 지금은 selection.profile(형식·해시·조합)과 판정 사유만 있다. 결과 파일 코덱·판정(selection.result)과 선정 계산
// (selection.calc, marketdata 의존)은 배치표 2번과 생산 일괄 명령 PR이 더한다.
dependencies {
    api(project(":autotrade-domain"))
    api(project(":autotrade-strategy"))
}
