package com.autotrade.marketdata.live;

import java.time.Duration;

/** 지연 실행 포트. 운영은 {@link JdkScheduler}, 테스트는 수동으로 시간을 넘기는 구현을 쓴다. */
public interface Scheduler {

    Cancellable schedule(Duration delay, Runnable task);

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
