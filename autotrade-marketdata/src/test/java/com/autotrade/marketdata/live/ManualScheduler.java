package com.autotrade.marketdata.live;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 테스트용 스케줄러. {@link #advance}로 시계와 함께 시간을 넘기면 그때까지 예약된 작업을 순서대로 실행한다. */
final class ManualScheduler implements Scheduler {

    private final MutableClock clock;
    private final List<Task> tasks = new ArrayList<>();
    private long order;

    ManualScheduler(MutableClock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Cancellable schedule(Duration delay, Runnable task) {
        Task t = new Task(clock.instant().plus(delay), order++, task);
        tasks.add(t);
        return () -> {
            synchronized (ManualScheduler.this) {
                tasks.remove(t);
            }
        };
    }

    /** 지금 실행할 수 있는 작업을 모두 실행한다. */
    void runDue() {
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.stream().filter(t -> !t.at.isAfter(clock.instant()))
                        .min(Comparator.comparing((Task t) -> t.at).thenComparingLong(t -> t.order)).orElse(null);
                if (next == null) {
                    return;
                }
                tasks.remove(next);
            }
            next.task.run();
        }
    }

    void advance(Duration d) {
        Instant target = clock.instant().plus(d);
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.stream().filter(t -> !t.at.isAfter(target))
                        .min(Comparator.comparing((Task t) -> t.at).thenComparingLong(t -> t.order)).orElse(null);
                if (next == null) {
                    break;
                }
                tasks.remove(next);
            }
            if (next.at.isAfter(clock.instant())) {
                clock.set(next.at);
            }
            next.task.run();
        }
        clock.set(target);
    }

    private record Task(Instant at, long order, Runnable task) {}
}
