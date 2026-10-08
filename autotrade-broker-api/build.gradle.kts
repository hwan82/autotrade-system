plugins {
    id("autotrade.pure-java-conventions")
}

// 증권사 플러그인(kis, kiwoom, toss)은 이 모듈의 인터페이스만 구현하고 서로 참조하지 않는다.
// 플러그인 모듈 자체는 2단계(시세)와 7단계(실전)에서 추가한다.
dependencies {
    api(project(":autotrade-domain"))
    api(project(":autotrade-marketdata"))
}
