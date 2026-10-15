plugins {
    id("autotrade.pure-java-conventions")
}

// 독립 라이브러리: 감시 에이전트 코어(MON-01·MON-05). 자동매매 도메인을 몰라야 한다(ArchitectureTest).
// 감시 프로세스 런타임에 들어가므로 외부 의존이 없고 JDK 허용 목록(D-086) 안 타입만 쓴다. OS·JVM 지표를 읽는 수집원(OSHI,
// java.lang.management)은 autotrade-monitor-sources에 둔다(D-090).
