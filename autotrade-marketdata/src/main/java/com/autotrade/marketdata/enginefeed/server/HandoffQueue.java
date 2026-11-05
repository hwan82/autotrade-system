package com.autotrade.marketdata.enginefeed.server;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 넣는 쪽 여럿·꺼내는 쪽 하나인 크기 제한 큐(ADR-016 2.5). 넣기는 잠금 없이 CAS만 하고 꺼내는 스레드를 깨우지 않는다(깨우기
 * 시스템 호출이 게이트웨이 수신 스레드의 지연이 되지 않게). 꺼내는 스레드는 비었을 때 {@link #POLL_NANOS}마다 스스로 깨어 본다.
 */
final class HandoffQueue<T> {

    /** 비었을 때 꺼내는 쪽이 쉬는 시간. 엔진 전달에 더해지는 지연의 상한이다 `[가정]`. */
    static final long POLL_NANOS = TimeUnit.MICROSECONDS.toNanos(500);

    private final ConcurrentLinkedQueue<T> items = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;

    HandoffQueue(int capacity) {
        this.capacity = capacity;
    }

    /** 자리가 없으면 넣지 않고 false. 기다리지 않는다. */
    boolean offer(T item) {
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            return false;
        }
        items.offer(item);
        return true;
    }

    /** 하나 꺼낸다. 비었으면 올 때까지 짧게 쉬며 다시 본다. */
    T take() throws InterruptedException {
        while (true) {
            T t = items.poll();
            if (t != null) {
                size.decrementAndGet();
                return t;
            }
            Thread.sleep(0, (int) POLL_NANOS);
        }
    }

    /** 있으면 하나 꺼내고 없으면 null. 기다리지 않는다. */
    T poll() {
        T t = items.poll();
        if (t != null) {
            size.decrementAndGet();
        }
        return t;
    }

    void clear() {
        while (items.poll() != null) {
            size.decrementAndGet();
        }
    }
}
