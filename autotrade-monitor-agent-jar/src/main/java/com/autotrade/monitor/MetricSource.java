package com.autotrade.monitor;

import java.util.List;

/**
 * 지표 수집원. 에이전트의 수집 스레드 하나가 주기마다 부른다(동시 호출 없음). 변화율(초당 바이트, GC 시간 비율)은 수집원이
 * 직전 호출과의 차이로 계산하므로 첫 호출에는 그런 값을 빼도 된다.
 *
 * <p>{@link RuntimeException}을 던지면 그 주기의 이 수집원 값만 빠지고, 스냅샷의 {@link MetricSnapshot#failedSources()}에
 * 이름이 남는다. 네이티브 호출 실패 같은 {@code LinkageError}는 수집원이 {@link RuntimeException}으로 감싸 던진다: 코어는 감시
 * 프로세스 런타임에 들어가 JDK 허용 목록(D-086) 밖 타입({@code LinkageError})을 잡지 못하고, 감싸지 않은 {@code Error}는 주기
 * 수집을 멈춘다. 표준 수집원({@code autotrade-monitor-sources})은 감싼다(D-090).
 */
public interface MetricSource {

    /** 화면과 실패 목록에 쓰는 짧은 이름. */
    String id();

    List<Reading> read();
}
