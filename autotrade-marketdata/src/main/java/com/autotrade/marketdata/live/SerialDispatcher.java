package com.autotrade.marketdata.live;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 수신자 호출을 한 스레드에서 순서대로 실행한다(MarketEventListener 계약). 큐가 가득 차면 기다리지 않고
 * {@link #offer}가 {@code false}를 돌려준다(생산자는 기다리지 않는다, broker-and-fault-design.md 5장).
 */
public interface SerialDispatcher extends AutoCloseable {

    boolean offer(Runnable task);

    @Override
    void close();

    /** 호출한 스레드에서 바로 실행한다. 테스트와 리플레이용. */
    static SerialDispatcher direct() {
        return new SerialDispatcher() {
            @Override
            public boolean offer(Runnable task) {
                task.run();
                return true;
            }

            @Override
            public void close() {}
        };
    }

    /** 데몬 스레드 하나와 크기 {@code capacity}의 큐. */
    static SerialDispatcher threaded(String threadName, int capacity) {
        return new Threaded(threadName, capacity);
    }

    final class Threaded implements SerialDispatcher {
        private static final Runnable POISON = () -> {};
        private static final java.time.Duration CLOSE_TIMEOUT = java.time.Duration.ofSeconds(5);
        private final BlockingQueue<Runnable> queue;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread worker;

        Threaded(String threadName, int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.worker = new Thread(this::loop, threadName);
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public boolean offer(Runnable task) {
            return !closed.get() && queue.offer(task);
        }

        private void loop() {
            while (true) {
                Runnable task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (task == POISON) {
                    return;
                }
                try {
                    task.run();
                } catch (RuntimeException e) {
                    System.getLogger(SerialDispatcher.class.getName())
                            .log(System.Logger.Level.WARNING, "시세 수신자 오류: {0}", e.getClass().getName());
                }
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // 이미 받은 이벤트는 다 넘긴 뒤 멈춘다(녹화 누락 방지). 수신자가 막혀 큐가 비지 않으면 끊는다
                try {
                    if (!queue.offer(POISON, CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        worker.interrupt();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    worker.interrupt();
                }
            }
        }
    }
}
