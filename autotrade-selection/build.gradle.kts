plugins {
    id("autotrade.pure-java-conventions")
}

// 선정 프로필·조합·선정 결과 계약(ADR-007 2.1). 실험실, 선정 결과 생산 일괄 명령, 실전 엔진이 같은 코드를 쓴다.
// selection.profile(형식·해시·조합), selection.result(결과 모델·내용 해시, 판정 사유), selection.calc(선정 계산·조건
// 카탈로그, 배치표 2번). 결과 파일 코덱과 유효성 판정(SelectionValidity)은 생산 일괄 명령·실전 엔진 읽기 PR이 더한다.
dependencies {
    api(project(":autotrade-domain"))
    api(project(":autotrade-strategy"))
    api(project(":autotrade-marketdata"))
}
