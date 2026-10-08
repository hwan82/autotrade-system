plugins {
    id("autotrade.pure-java-conventions")
}

// 독립 라이브러리: PC 자원·JVM 감시 에이전트(OSHI 등). 자동매매 도메인을 몰라야 한다(ArchitectureTest).
// 구현은 리스크·운영 작업(감시·킬스위치)에서 추가한다.
