package com.autotrade.marketdata.live;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * 실시간 소스가 쓰는 실행 자원 묶음. 프로세스 진입점이 운영용(JDK 구현)으로, 테스트는 가짜로 만든다.
 *
 * @param dispatchers 소스 이름 → 그 소스 전용 수신자 디스패처
 */
public record LiveRuntime(
        WsTransport ws, HttpPort http, Clock clock, Scheduler scheduler, Function<String, SerialDispatcher> dispatchers) {

    public LiveRuntime {
        Objects.requireNonNull(ws, "ws");
        Objects.requireNonNull(http, "http");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(dispatchers, "dispatchers");
    }
}
