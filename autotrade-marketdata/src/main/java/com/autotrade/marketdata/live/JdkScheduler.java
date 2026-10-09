package com.autotrade.marketdata.live;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 데몬 스레드 하나로 도는 스케줄러. 프로세스 진입점이 만들고 닫는다. */
public final class JdkScheduler implements Scheduler, AutoCloseable {

    private final ScheduledExecutorService executor;

    public JdkScheduler(String threadName) {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        var future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
