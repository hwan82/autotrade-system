plugins {
    id("autotrade.pure-java-conventions")
}

// 독립 라이브러리: Slack(긴급)·카카오톡(정보성) 알림. 자동매매 도메인을 몰라야 한다(ArchitectureTest).
// 구현은 리스크·운영 작업(알림)에서 추가한다.
