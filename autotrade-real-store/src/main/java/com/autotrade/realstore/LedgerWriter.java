package com.autotrade.realstore;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 원장 묶음 하나(계좌 하나, 또는 공통 원장)의 쓰기 스레드(ADR-015 2.1 원칙 5, 2.4). 대기열에서 받은 줄을 붙이고, 그때까지 쌓인 줄을
 * 원장마다 한 번의 강제 기록({@code FileChannel.force(true)})으로 묶은 뒤 "기록됨"을 돌려준다(묶음 커밋). 계좌마다 따로라 한 계좌의
 * 쓰기 지연·실패가 다른 계좌를 늦추지 않는다.
 *
 * <p>호출자는 {@link Pending}으로 기다리거나(상한은 호출자가 정함) 기다리지 않는다. 상한을 넘긴 호출자는 {@link Pending#cancel()}로
 * 아직 쓰지 않은 줄을 대기열에서 뺄 수 있다(2.5 "대기열에 남은 그 줄은 취소").
 */
final class LedgerWriter implements AutoCloseable {

    private static final Logger LOG = System.getLogger(LedgerWriter.class.getName());

    private final String name;
    private final long boot;
    private final Clock clock;
    private final int capacity;
    private final LinkedBlockingQueue<Pending> queue;
    private final Thread thread;
    private final FailureListener onFailure;
    private volatile boolean closing;
    /** 시험 전용: 있으면 강제 기록 직전에 이 문이 열릴 때까지 기다린다(느린 디스크 흉내). 운영 코드는 쓰지 않는다. */
    private volatile java.util.concurrent.CountDownLatch forceGate;

    /** 원장 쓰기가 실패했다(쓰기 스레드에서 부름). 저장소 장애 표시와 알림을 맡는다. */
    @FunctionalInterface
    interface FailureListener {
        void failed(LedgerFile ledger, Exception cause);
    }

    LedgerWriter(String name, long boot, Clock clock, int capacity, FailureListener onFailure) {
        this.name = Objects.requireNonNull(name, "name");
        this.boot = boot;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.thread = Thread.ofPlatform().name("engine-store-" + name).daemon(true).unstarted(this::run);
        this.thread.start();
    }

    /**
     * 줄 하나를 대기열에 넣는다. 기록 시각은 지금({@link Clock}). 대기열이 가득 찼거나 닫는 중이면 실패한 {@link Pending}을
     * 돌려준다(던지지 않음).
     */
    Pending submit(LedgerFile ledger, String type, ObjectNode body) {
        Pending p = new Pending(ledger, type, body, clock.instant());
        if (closing) {
            p.done.completeExceptionally(new IOException("엔진 저장소가 닫히는 중입니다"));
            return p;
        }
        if (!queue.offer(p)) {
            p.done.completeExceptionally(new QueueFull(name + " 쓰기 대기열이 가득 찼습니다(" + capacity + "줄)"));
        }
        return p;
    }

    /** 시험 전용(느린 강제 기록 흉내). */
    void gateForce(java.util.concurrent.CountDownLatch gate) {
        this.forceGate = gate;
    }

    /** 아직 쓰지 않은 줄 수. */
    int backlog() {
        return queue.size();
    }

    private void run() {
        List<Pending> batch = new ArrayList<>();
        while (true) {
            try {
                Pending first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (closing && queue.isEmpty()) {
                        return;
                    }
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch);
                write(batch);
            } catch (InterruptedException e) {
                // 닫기가 기다리지 못하고 끊었다. 남은 줄은 실패로 돌려준다
                for (Pending p : queue) {
                    p.done.completeExceptionally(new IOException("엔진 저장소 쓰기가 중단됐습니다"));
                }
                return;
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "엔진 저장소 쓰기 스레드 오류(" + name + ")", e);
                for (Pending p : batch) {
                    p.done.completeExceptionally(e);
                }
            } finally {
                batch.clear();
            }
        }
    }

    private void write(List<Pending> batch) {
        List<LedgerFile> touched = new ArrayList<>();
        List<Pending> written = new ArrayList<>();
        for (Pending p : batch) {
            if (!p.take()) {
                continue; // 호출자가 상한을 넘겨 취소했다
            }
            try {
                p.rseq = p.ledger.append(boot, p.at, p.type, p.body);
                if (!touched.contains(p.ledger)) {
                    touched.add(p.ledger);
                }
                written.add(p);
            } catch (IOException | RuntimeException e) {
                p.done.completeExceptionally(e);
                // 붙이기 실패는 그 원장을 마지막 강제 기록 길이로 되돌린다. 이 묶음에서 같은 원장에 먼저 붙인 줄도 함께 지워졌으므로
                // "기록됨"으로 끝내지 않고 실패로 돌려준다(PR #248 architect 발견 1: 지워진 올림 감사로 Guard가 올라가지 않게).
                // 되돌린 뒤 원장의 마지막 rseq는 디스크 기준이다. 그 이하 줄은 이미 강제 기록됐다(거래일이 바뀌며 앞 날짜 파일을 강제
                // 기록하고 닫은 경우)라 지워지지 않았으므로 그대로 둔다(독립 QA Q5). 되돌리기조차 실패했으면(broken) 모두 실패로 둔다
                boolean broken = p.ledger.broken();
                long durable = p.ledger.lastRseq();
                for (int i = written.size() - 1; i >= 0; i--) {
                    Pending earlier = written.get(i);
                    if (earlier.ledger == p.ledger && (broken || earlier.rseq > durable)) {
                        written.remove(i);
                        earlier.done.completeExceptionally(new IOException("같은 묶음의 뒤 줄 기록 실패로 되돌려짐: "
                                + p.ledger.stream(), e));
                    }
                }
                onFailure.failed(p.ledger, e instanceof IOException io ? io : new IOException(e.getMessage(), e));
            }
        }
        java.util.concurrent.CountDownLatch gate = forceGate;
        if (gate != null && !touched.isEmpty()) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<LedgerFile> failed = new ArrayList<>();
        for (LedgerFile ledger : touched) {
            try {
                ledger.force();
            } catch (IOException e) {
                failed.add(ledger);
                onFailure.failed(ledger, e);
            }
        }
        for (Pending p : written) {
            if (failed.contains(p.ledger)) {
                p.done.completeExceptionally(new IOException("원장 강제 기록 실패: " + p.ledger.stream()));
            } else {
                p.done.complete(p.rseq);
            }
        }
    }

    /** 남은 줄을 최대 {@code wait} 동안 쓰고 스레드를 멈춘다. 다 쓰지 못한 줄 수를 돌려준다. */
    int drainAndStop(Duration wait) {
        closing = true;
        try {
            thread.join(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            int left = queue.size();
            thread.interrupt();
            return Math.max(left, 1);
        }
        return 0;
    }

    @Override
    public void close() {
        drainAndStop(Duration.ofSeconds(2));
    }

    /** 대기열에 넣은 줄 하나. */
    static final class Pending {
        private static final int QUEUED = 0;
        private static final int TAKEN = 1;
        private static final int CANCELLED = 2;

        final LedgerFile ledger;
        final String type;
        final ObjectNode body;
        final Instant at;
        final CompletableFuture<Long> done = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private long rseq;

        Pending(LedgerFile ledger, String type, ObjectNode body, Instant at) {
            this.ledger = ledger;
            this.type = type;
            this.body = body;
            this.at = at;
        }

        private boolean take() {
            return state.compareAndSet(QUEUED, TAKEN);
        }

        /**
         * 아직 쓰기 스레드가 집지 않았으면 취소한다. 취소했으면 true(그 줄은 원장에 쓰이지 않는다). false면 이미 쓰는 중이거나 쓰였다.
         */
        boolean cancel() {
            boolean cancelled = state.compareAndSet(QUEUED, CANCELLED);
            if (cancelled) {
                done.completeExceptionally(new IOException("기록 상한 초과로 취소"));
            }
            return cancelled;
        }
    }

    /** 대기열 상한 초과(2.5 내림 감사 계좌당 1,000줄 등). */
    static final class QueueFull extends IOException {

        private static final long serialVersionUID = 1L;

        QueueFull(String message) {
            super(message);
        }
    }
}
