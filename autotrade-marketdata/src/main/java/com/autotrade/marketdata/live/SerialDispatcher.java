package com.autotrade.marketdata.live;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
                queue.clear();
                if (!queue.offer(POISON)) {
                    worker.interrupt();
                }
            }
        }
    }
}
