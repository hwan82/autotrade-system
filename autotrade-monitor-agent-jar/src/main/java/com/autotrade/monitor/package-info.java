/**
 * 시스템 감시 에이전트 코어(MON-01·MON-05): 수집원({@link com.autotrade.monitor.MetricSource})이 돌려준 지표를 주기마다 모아
 * 임계({@link com.autotrade.monitor.Threshold})와 비교하고, 수준이 바뀌면 이벤트를 낸다.
 *
 * <p>다른 프로젝트에서 재사용할 수 있도록 자동매매 패키지에 의존하지 않는다(ArchitectureTest). 감시 프로세스 런타임에
 * 들어가는 모듈이라 실전 경로 리플렉션 금지 규칙(D-086)의 JDK 허용 목록 안 타입만 쓴다. 그래서 OS·JVM 지표를 실제로 읽는
 * 수집원(OSHI, {@code java.lang.management})은 이 모듈에 두지 않고 {@code autotrade-monitor-sources}에 둔다(D-090).
 */
package com.autotrade.monitor;
