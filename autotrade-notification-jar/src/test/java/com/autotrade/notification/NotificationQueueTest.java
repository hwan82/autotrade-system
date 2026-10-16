package com.autotrade.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationQueueTest {

    private static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");

    /** 손으로 움직이는 시계. */
    static final class StepClock extends Clock {
        volatile Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 받은 알림을 모으는 외부 채널. 동작을 바꿔 끼울 수 있다. */
    static final class Sink implements Channel {
        final List<Notification> got = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Behavior> behavior = new AtomicReference<>(n -> SendResult.ok());

        interface Behavior {
            SendResult send(Notification n) throws InterruptedException;
        }

        @Override
        public String name() {
            return "sink";
        }

        @Override
        public SendResult send(Notification n) throws InterruptedException {
            got.add(n);
            return behavior.get().send(n);
        }
    }

    private final StepClock clock = new StepClock();
    private final Sink sink = new Sink();
    private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
    private final LogChannel log = new LogChannel(NotifierTest.recordingLogger(logLines));
    private NotificationQueue queue;

    private NotificationQueue queue() {
        queue = new NotificationQueue(Notifier.of(List.of(sink, log), List.of(sink, log)), EventRoutes.defaults(), clock,
                ZoneOffset.UTC, "test", log).start();
        return queue;
    }

    @AfterEach
    void close() {
        if (queue != null) {
            queue.close(Duration.ofSeconds(1));
        }
    }

    private void awaitGot(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sink.got.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(sink.got).hasSizeGreaterThanOrEqualTo(n);
    }

    @Test
    void 등급은_종류로만_정하고_보내는_스레드에서_보낸다() throws Exception {
        AtomicReference<String> thread = new AtomicReference<>();
        sink.behavior.set(n -> {
            thread.set(Thread.currentThread().getName());
            return SendResult.ok();
        });
        assertThat(queue().offer(EventRoutes.PROCESS_STALE, "engine", "엔진 멈춤", "")).isTrue();
        awaitGot(1);
        assertThat(sink.got.get(0).severity()).isEqualTo(Severity.URGENT);
        assertThat(sink.got.get(0).kind()).isEqualTo(EventRoutes.PROCESS_STALE);
        assertThat(thread.get()).isEqualTo("notification-test").isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void 채널이_막혀도_넣는_쪽은_기다리지_않고_큐가_차면_버린다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        sink.behavior.set(n -> {
            release.await();
            return SendResult.ok();
        });
        NotificationQueue q = queue();
        long start = System.nanoTime();
        int accepted = 0;
        for (int i = 0; i < 200; i++) {
            if (q.offer(EventRoutes.ERROR, "s" + i, "오류 " + i, "")) {
                accepted++;
            }
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        // 전체 상한(창 하나에 20건)이 먼저 걸린다
        assertThat(accepted).isEqualTo(NotificationQueue.MAX_PER_WINDOW);
        assertThat(q.droppedTotal()).isEqualTo(200 - NotificationQueue.MAX_PER_WINDOW);
        release.countDown();
    }

    @Test
    void 채널이_Error를_던져도_넣는_쪽으로_번지지_않고_다음_알림은_보낸다() throws Exception {
        sink.behavior.set(n -> {
            if (n.title().equals("첫째")) {
                throw new StackOverflowError();
            }
            return SendResult.ok();
        });
        NotificationQueue q = queue();
        assertThat(q.offer(EventRoutes.ERROR, "a", "첫째", "")).isTrue();
        assertThat(q.offer(EventRoutes.ERROR, "b", "둘째", "")).isTrue();
        awaitGot(2);
        assertThat(sink.got).extracting(Notification::title).containsExactly("첫째", "둘째");
        // Error가 난 알림은 로그에 남는다
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (logLines.stream().noneMatch(l -> l.contains("첫째")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(logLines).anyMatch(l -> l.startsWith("ERROR 알림(error) [긴급] 첫째"));
    }

    @Test
    void 형식이_틀린_알림도_던지지_않고_거절한다() {
        NotificationQueue q = queue();
        assertThat(q.offer("Bad Kind", "a", "제목", "")).isFalse();
        assertThat(q.offer(EventRoutes.ERROR, "a", " ", "")).isFalse();
        assertThat(q.offer(null, null, null, null)).isFalse();
    }

    @Test
    void 같은_대상_같은_종류는_10분_안에_한_번만_보내고_다음에_생략_건수를_붙인다() throws Exception {
        assertThat(NotificationQueue.QUIET).isEqualTo(Duration.ofMinutes(10));
        NotificationQueue q = queue();
        assertThat(q.offer(EventRoutes.READING_MISSING, "readings", "값 빠짐", "")).isTrue();
        clock.now = T0.plusSeconds(60);
        assertThat(q.offer(EventRoutes.READING_MISSING, "readings", "값 빠짐", "")).isFalse();
        clock.now = T0.plus(Duration.ofMinutes(10)).minusMillis(1);
        assertThat(q.offer(EventRoutes.READING_MISSING, "readings", "값 빠짐", "")).isFalse();
        assertThat(q.suppressedTotal()).isEqualTo(2);
        clock.now = T0.plus(Duration.ofMinutes(10));
        assertThat(q.offer(EventRoutes.READING_MISSING, "readings", "값 빠짐", "본문")).isTrue();
        awaitGot(2);
        assertThat(sink.got.get(1).body()).isEqualTo("본문\n(직전 10분 동안 같은 알림 2건 생략)");
    }

    @Test
    void 종류가_바뀌면_억제하지_않는다() throws Exception {
        NotificationQueue q = queue();
        assertThat(q.offer(EventRoutes.PROCESS_STALE, "engine", "엔진 멈춤", "")).isTrue();
        assertThat(q.offer(EventRoutes.PROCESS_RECOVERED, "engine", "엔진 회복", "")).isTrue();
        assertThat(q.offer(EventRoutes.PROCESS_STALE, "engine", "엔진 멈춤", "")).isTrue();
        // 다른 대상은 따로 센다
        assertThat(q.offer(EventRoutes.PROCESS_STALE, "gateway", "게이트웨이 멈춤", "")).isTrue();
        awaitGot(4);
    }

    @Test
    void 시계가_뒤로_가면_억제_간격이_찬_것으로_본다() {
        NotificationQueue q = queue();
        clock.now = T0.plusSeconds(600);
        assertThat(q.offer(EventRoutes.READING_MISSING, "readings", "값 빠짐", "")).isTrue();
        clock.now = T0;
        assertThat(q.offer(EventRoutes.READING_MISSING, "readings", "값 빠짐", "")).isTrue();
    }

    @Test
    void 상한으로_버린_건수는_다음에_받는_알림에_붙는다() throws Exception {
        NotificationQueue q = queue();
        for (int i = 0; i < NotificationQueue.MAX_PER_WINDOW + 3; i++) {
            q.offer(EventRoutes.ERROR, "s" + i, "오류", "");
        }
        assertThat(q.droppedTotal()).isEqualTo(3);
        clock.now = T0.plus(NotificationQueue.QUIET);
        assertThat(q.offer(EventRoutes.ERROR, "later", "오류", "")).isTrue();
        awaitGot(NotificationQueue.MAX_PER_WINDOW + 1);
        assertThat(sink.got.get(NotificationQueue.MAX_PER_WINDOW).body()).isEqualTo("(알림이 몰려 다른 알림 3건 생략(로그 확인))");
    }

    @Test
    void 카카오_갱신_거절을_보면_재동의_알림을_한_번만_넣는다() throws Exception {
        Instant now = T0;
        FakeHttp kakaoHttp = new FakeHttp().reply(400, "{\"error\":\"invalid_grant\"}");
        KakaoMemoChannel kakao = new KakaoMemoChannel("REST-KEY-PH", "", "http://localhost",
                new KakaoToken("ACCESS-OLD", "REFRESH-OLD", now), KakaoTokenStore.none(), kakaoHttp, clock);
        queue = new NotificationQueue(Notifier.of(List.of(sink, log), List.of(kakao, sink, log)), EventRoutes.defaults(),
                clock, ZoneOffset.UTC, "test", log).start();
        queue.offer(EventRoutes.TAKE_PROFIT, "a", "익절 1", "");
        queue.offer(EventRoutes.TAKE_PROFIT, "b", "익절 2", "");
        queue.offer(EventRoutes.TAKE_PROFIT, "c", "익절 3", "");
        awaitGot(4);
        Thread.sleep(100);
        assertThat(sink.got).filteredOn(n -> n.kind().equals(EventRoutes.NOTIFICATION_CHANNEL)).singleElement()
                .satisfies(n -> {
                    assertThat(n.severity()).isEqualTo(Severity.URGENT);
                    assertThat(n.title()).contains("재동의");
                    assertThat(n.body()).doesNotContain("REFRESH-OLD");
                });
        assertThat(kakaoHttp.requests).hasSize(1);
    }

    @Test
    void 닫으면_새_알림을_받지_않고_못_보낸_알림은_로그에_남긴다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        sink.behavior.set(n -> {
            entered.countDown();
            new CountDownLatch(1).await(); // 중단될 때까지 막힘
            return SendResult.ok();
        });
        NotificationQueue q = queue();
        q.offer(EventRoutes.ERROR, "a", "막힌 것", "");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        q.offer(EventRoutes.ERROR, "b", "남은 것", "");
        long start = System.nanoTime();
        q.close(Duration.ofMillis(300));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(q.offer(EventRoutes.ERROR, "c", "닫힌 뒤", "")).isFalse();
        assertThat(logLines).anyMatch(l -> l.contains("막힌 것")).anyMatch(l -> l.contains("남은 것"))
                .noneMatch(l -> l.contains("닫힌 뒤"));
        queue = null;
    }
}
