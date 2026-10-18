package com.autotrade.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.monitor.Threshold.Direction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try") // 로그 핸들러를 되돌리는 자원(capture)은 시험 본문에서 쓰지 않는다
class MonitorAgentTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);

    /** 값을 바꿔 넣을 수 있는 가짜 수집원(임계 초과 주입). */
    static final class InjectedSource implements MetricSource {
        final AtomicReference<Double> cpu = new AtomicReference<>(10.0);
        boolean fail;

        @Override
        public String id() {
            return "injected";
        }

        @Override
        public List<Reading> read() {
            if (fail) {
                throw new IllegalStateException("수집 실패 주입");
            }
            return List.of(new Reading("cpu.system", cpu.get(), "%"), new Reading("jvm.threads", 42, "개"));
        }
    }

    private final InjectedSource source = new InjectedSource();

    private MonitorAgent agent(int history) {
        return new MonitorAgent(CLOCK, List.of(source), List.of(new Threshold("cpu.system", Direction.ABOVE, 85, 95, 2)),
                history, 3);
    }

    @Test
    void 임계_초과를_주입하면_이벤트가_듣는_쪽과_최근_기록에_간다() {
        MonitorAgent agent = agent(10);
        List<ThresholdEvent> heard = new ArrayList<>();
        agent.addListener((snapshot, events) -> heard.addAll(events));

        agent.sampleOnce();
        source.cpu.set(97.0);
        agent.sampleOnce();
        MetricSnapshot snap = agent.sampleOnce();

        assertThat(heard).singleElement().satisfies(e -> {
            assertThat(e.reading()).isEqualTo("cpu.system");
            assertThat(e.to()).isEqualTo(Level.CRITICAL);
            assertThat(e.at()).isEqualTo(CLOCK.instant());
        });
        assertThat(agent.recentEvents()).isEqualTo(heard);
        assertThat(snap.levels()).containsEntry("cpu.system", Level.CRITICAL);
        assertThat(snap.readings()).extracting(Reading::name).containsExactly("cpu.system", "jvm.threads");
    }

    @Test
    void 수집원_예외는_그_주기의_실패_목록에만_남고_다음_주기를_막지_않는다() {
        MonitorAgent agent = agent(10);
        assertThat(agent.lastSampleAt()).isEmpty();
        source.fail = true;
        MetricSnapshot failed = agent.sampleOnce();
        assertThat(failed.failedSources()).containsExactly("injected");
        assertThat(failed.readings()).isEmpty();
        source.fail = false;
        assertThat(agent.sampleOnce().readings()).hasSize(2);
    }

    @Test
    void 듣는_쪽_예외는_다른_듣는_쪽과_수집을_막지_않고_뗄_수_있다() throws Exception {
        MonitorAgent agent = agent(10);
        agent.addListener((s, e) -> {
            throw new IllegalStateException("리스너 실패 주입");
        });
        List<MetricSnapshot> heard = new ArrayList<>();
        AutoCloseable handle = agent.addListener((s, e) -> heard.add(s));
        agent.sampleOnce();
        handle.close();
        agent.sampleOnce();
        assertThat(heard).hasSize(1);
        assertThat(agent.history()).hasSize(2);
    }

    /** 주기 수집 중 수집원이나 리스너가 Error를 던져도 다음 주기가 돈다(risk-ops PR #114 R2). */
    @Test
    void 수집원_Error는_그_주기_실패로만_남고_주기_수집이_이어진다() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        MetricSource broken = new MetricSource() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public List<Reading> read() {
                reads.incrementAndGet();
                throw new NoClassDefFoundError("수집원 Error 주입");
            }
        };
        try (MonitorAgent agent = new MonitorAgent(CLOCK, List.of(broken, source), List.of(), 10, 3)) {
            CountDownLatch five = new CountDownLatch(5);
            List<MetricSnapshot> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
            agent.addListener((s, e) -> {
                heard.add(s);
                five.countDown();
            });
            agent.start(Duration.ofMillis(10));
            assertThat(five.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reads.get()).isGreaterThanOrEqualTo(5);
            assertThat(heard).allSatisfy(s -> {
                assertThat(s.failedSources()).containsExactly("broken");
                assertThat(s.readings()).hasSize(2);
            });
        }
    }

    @Test
    void 리스너_Error도_다음_주기를_막지_않는다() throws Exception {
        try (MonitorAgent agent = agent(10)) {
            agent.addListener((s, e) -> {
                throw new StackOverflowError("리스너 Error 주입");
            });
            CountDownLatch five = new CountDownLatch(5);
            agent.addListener((s, e) -> five.countDown());
            agent.start(Duration.ofMillis(10));
            assertThat(five.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(agent.lastSampleAt()).contains(CLOCK.instant());
        }
    }

    @Test
    void 기록은_정한_크기만_들고_오래된_것부터_버린다() {
        MonitorAgent agent = agent(2);
        agent.sampleOnce();
        source.cpu.set(50.0);
        agent.sampleOnce();
        source.cpu.set(60.0);
        agent.sampleOnce();
        assertThat(agent.history()).extracting(s -> s.readings().get(0).value()).containsExactly(50.0, 60.0);
        assertThat(agent.latest()).get().extracting(s -> s.readings().get(0).value()).isEqualTo(60.0);

        // 이벤트 기록도 크기 3
        for (int i = 0; i < 4; i++) {
            source.cpu.set(99.0);
            agent.sampleOnce();
            agent.sampleOnce();
            source.cpu.set(10.0);
            agent.sampleOnce();
            agent.sampleOnce();
        }
        assertThat(agent.recentEvents()).hasSize(3);
    }

    @Test
    void 주기_수집은_데몬_스레드에서_돌고_닫으면_멈춘다() throws Exception {
        MonitorAgent agent = agent(10);
        CountDownLatch twice = new CountDownLatch(2);
        AtomicReference<Thread> thread = new AtomicReference<>();
        agent.addListener((s, e) -> {
            thread.set(Thread.currentThread());
            twice.countDown();
        });
        agent.start(Duration.ofMillis(10));
        assertThat(twice.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(thread.get().isDaemon()).isTrue();
        assertThat(thread.get().getPriority()).isEqualTo(Thread.MIN_PRIORITY);
        assertThatThrownBy(() -> agent.start(Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        agent.close();
        thread.get().join(5000);
        assertThat(thread.get().isAlive()).isFalse();
    }

    @Test
    void 같은_수집원_실패는_상태가_바뀔_때만_WARN이고_반복은_DEBUG다() {
        // OSHI가 안 되는 PC에서 매 주기 WARN이 쌓이지 않게(risk-ops PR #114 K5)
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        MetricSource flaky = new MetricSource() {
            @Override
            public String id() {
                return "system";
            }

            @Override
            public List<Reading> read() {
                RuntimeException e = failure.get();
                if (e != null) {
                    throw e;
                }
                return List.of(new Reading("cpu.system", 10, "%"));
            }
        };
        MonitorAgent agent = new MonitorAgent(CLOCK, List.of(flaky), List.of(), 10, 3);
        List<LogRecord> logs = new ArrayList<>();
        Logger jul = Logger.getLogger(MonitorAgent.class.getName());
        java.util.logging.Level oldLevel = jul.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(java.util.logging.Level.ALL);
        jul.setLevel(java.util.logging.Level.ALL);
        jul.addHandler(handler);
        try {
            agent.sampleOnce();
            failure.set(new IllegalStateException("OSHI 없음"));
            for (int i = 0; i < 100; i++) {
                assertThat(agent.sampleOnce().failedSources()).containsExactly("system");
            }
            failure.set(new UnsupportedOperationException("다른 실패"));
            agent.sampleOnce();
            agent.sampleOnce();
            failure.set(null);
            agent.sampleOnce();
            agent.sampleOnce();
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(oldLevel);
        }

        assertThat(logs).filteredOn(r -> r.getLevel().equals(java.util.logging.Level.WARNING))
                .extracting(LogRecord::getMessage)
                .containsExactly("수집원 실패: system (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다",
                        "수집원 실패 바뀜: system (IllegalStateException → UnsupportedOperationException)");
        assertThat(logs).filteredOn(r -> r.getLevel().equals(java.util.logging.Level.INFO))
                .extracting(LogRecord::getMessage)
                .containsExactly("수집원 회복: system (UnsupportedOperationException 2회 연속 실패 뒤)");
        assertThat(logs).filteredOn(r -> r.getLevel().intValue() < java.util.logging.Level.INFO.intValue())
                .hasSize(99 + 1)
                .last().extracting(LogRecord::getMessage).isEqualTo("수집원 실패 계속: system (UnsupportedOperationException, 2회째)");
    }

    @Test
    void DEBUG가_꺼져_있어도_연속_실패_횟수는_세어_회복_로그에_남는다() {
        // 운영 기본 수준(INFO)에서는 반복 줄을 만들지 않지만 횟수는 센다(architect PR #119 R1, risk-ops K5)
        MonitorAgent agent = agent(10);
        List<LogRecord> logs = new ArrayList<>();
        Logger jul = Logger.getLogger(MonitorAgent.class.getName());
        java.util.logging.Level oldLevel = jul.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(java.util.logging.Level.ALL);
        jul.setLevel(java.util.logging.Level.INFO);
        jul.addHandler(handler);
        try {
            source.fail = true;
            for (int i = 0; i < 3; i++) {
                agent.sampleOnce();
            }
            source.fail = false;
            agent.sampleOnce();
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(oldLevel);
        }

        assertThat(logs).extracting(LogRecord::getMessage).containsExactly(
                "수집원 실패: injected (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다",
                "수집원 회복: injected (IllegalStateException 3회 연속 실패 뒤)");
    }

    /** 수집원 하나(id만 다른) 실패 주입. */
    private static MetricSource failing(String id, AtomicReference<RuntimeException> failure) {
        return new MetricSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Reading> read() {
                RuntimeException e = failure.get();
                if (e != null) {
                    throw e;
                }
                return List.of();
            }
        };
    }

    @Test
    void WARN과_INFO를_내지_못하면_다음_주기에_다시_내고_그_주기_판정은_이어_간다() {
        // 로그 핸들러가 던져 첫 WARN이 사라지면 다음 주기에 다시 낸다(PR #119 QA Q1).
        // 출력 실패로 그 주기의 스냅샷·임계 판정이 끊기면 안 된다(architect PR #155 F1).
        // 출력 실패와 상관없이 연속 실패 횟수는 실제대로 센다(architect PR #155 N5, risk-ops K3)
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicReference<Double> cpu = new AtomicReference<>(97.0);
        MetricSource healthy = new MetricSource() {
            @Override
            public String id() {
                return "cpu";
            }

            @Override
            public List<Reading> read() {
                return List.of(new Reading("cpu.system", cpu.get(), "%"));
            }
        };
        MonitorAgent agent = new MonitorAgent(CLOCK, List.of(failing("a", failure), failing("b", failure), healthy),
                List.of(new Threshold("cpu.system", Direction.ABOVE, 85, 95, 1)), 10, 3);
        List<String> logs = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger throwNext = new java.util.concurrent.atomic.AtomicInteger();
        Logger jul = Logger.getLogger(MonitorAgent.class.getName());
        java.util.logging.Level oldLevel = jul.getLevel();
        boolean oldParent = jul.getUseParentHandlers();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (throwNext.getAndDecrement() > 0) {
                    throw new IllegalStateException("핸들러 예외 주입");
                }
                logs.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(java.util.logging.Level.ALL);
        jul.setLevel(java.util.logging.Level.INFO);
        jul.setUseParentHandlers(false);
        jul.addHandler(handler);
        try {
            failure.set(new IllegalStateException("주입"));
            // 핸들러가 계속 던져도 매 주기 스냅샷과 임계 판정이 나온다
            throwNext.set(Integer.MAX_VALUE);
            for (int i = 0; i < 5; i++) {
                MetricSnapshot snap = agent.sampleOnce();
                assertThat(snap.failedSources()).containsExactly("a", "b");
                assertThat(snap.levels()).containsEntry("cpu.system", Level.CRITICAL);
            }
            assertThat(agent.history()).hasSize(5);
            assertThat(logs).isEmpty();

            // 핸들러가 살아나면 a·b의 첫 WARN이 한 번씩 나온다
            throwNext.set(0);
            agent.sampleOnce();
            agent.sampleOnce();
            assertThat(logs).containsExactly(
                    "WARNING 수집원 실패: a (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다",
                    "WARNING 수집원 실패: b (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다");

            logs.clear();
            failure.set(null);
            throwNext.set(1); // a의 회복 INFO만 출력 실패
            agent.sampleOnce();
            // 실패는 7주기 이어졌다(핸들러가 던진 5주기 포함)
            assertThat(logs).containsExactly("INFO 수집원 회복: b (IllegalStateException 7회 연속 실패 뒤)");
            agent.sampleOnce();
            agent.sampleOnce();
            assertThat(logs).containsExactly(
                    "INFO 수집원 회복: b (IllegalStateException 7회 연속 실패 뒤)",
                    "INFO 수집원 회복: a (IllegalStateException 7회 연속 실패 뒤)");
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(oldLevel);
            jul.setUseParentHandlers(oldParent);
        }
    }

    /** 감시 에이전트 로거에 핸들러를 붙인다. 돌려준 것을 닫으면 원래대로 돌린다. */
    private static AutoCloseable capture(Handler handler, java.util.logging.Level level) {
        Logger jul = Logger.getLogger(MonitorAgent.class.getName());
        java.util.logging.Level oldLevel = jul.getLevel();
        boolean oldParent = jul.getUseParentHandlers();
        handler.setLevel(java.util.logging.Level.ALL);
        jul.setLevel(level);
        jul.setUseParentHandlers(false);
        jul.addHandler(handler);
        return () -> {
            jul.removeHandler(handler);
            jul.setLevel(oldLevel);
            jul.setUseParentHandlers(oldParent);
        };
    }

    /** {@code throwNext}가 양수인 동안 한 번에 하나씩 던지고, 아니면 "수준 문구"로 모은다. */
    private static Handler flakyHandler(List<String> logs, java.util.concurrent.atomic.AtomicInteger throwNext) {
        return new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (throwNext.getAndDecrement() > 0) {
                    throw new IllegalStateException("핸들러 예외 주입");
                }
                logs.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    @Test
    void 실패_종류_바뀜_WARN을_내지_못하면_다음_주기에_한_번_다시_낸다() throws Exception {
        // PR #155 QA Q1(변이 M9): "바뀜" 줄이 첫 실패 줄로 바뀌거나 사라지면 안 된다
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        MonitorAgent agent = new MonitorAgent(CLOCK, List.of(failing("a", failure)), List.of(), 10, 3);
        List<String> logs = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger throwNext = new java.util.concurrent.atomic.AtomicInteger();
        try (AutoCloseable restore = capture(flakyHandler(logs, throwNext), java.util.logging.Level.INFO)) {
            failure.set(new IllegalStateException("첫 실패"));
            agent.sampleOnce();
            failure.set(new UnsupportedOperationException("다른 실패"));
            throwNext.set(1);
            agent.sampleOnce();
            assertThat(logs).containsExactly(
                    "WARNING 수집원 실패: a (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다");
            for (int i = 0; i < 3; i++) {
                agent.sampleOnce();
            }
            failure.set(null);
            agent.sampleOnce();
        }
        assertThat(logs).containsExactly(
                "WARNING 수집원 실패: a (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다",
                "WARNING 수집원 실패 바뀜: a (IllegalStateException → UnsupportedOperationException)",
                "INFO 수집원 회복: a (UnsupportedOperationException 4회 연속 실패 뒤)");
    }

    @Test
    void DEBUG가_켜진_주기에도_내지_못한_WARN을_DEBUG_줄_앞에_다시_낸다() throws Exception {
        // PR #163 QA Q2(변이 QM12): 반복 실패 주기에 DEBUG 줄이 생겨도 들고 있던 WARN을 버리거나 뒤로 미루지 않는다
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        MonitorAgent agent = new MonitorAgent(CLOCK, List.of(failing("a", failure)), List.of(), 10, 3);
        List<String> logs = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger throwNext = new java.util.concurrent.atomic.AtomicInteger();
        try (AutoCloseable restore = capture(flakyHandler(logs, throwNext), java.util.logging.Level.ALL)) {
            failure.set(new IllegalStateException("첫 실패"));
            throwNext.set(1);
            agent.sampleOnce();
            assertThat(logs).isEmpty();
            agent.sampleOnce();
            agent.sampleOnce();
        }
        assertThat(logs).containsExactly(
                "WARNING 수집원 실패: a (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다",
                "FINE 수집원 실패 계속: a (IllegalStateException, 2회째)",
                "FINE 수집원 실패 계속: a (IllegalStateException, 3회째)");
    }

    @Test
    void 회복_INFO를_내지_못한_채_다시_실패하면_새_첫_실패로_세고_낡은_회복_줄은_버린다() throws Exception {
        // PR #155 QA Q2: 사이의 성공 주기가 사라지고 횟수가 이어서 세지면 안 된다
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        MonitorAgent agent = new MonitorAgent(CLOCK, List.of(failing("a", failure)), List.of(), 10, 3);
        List<String> logs = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger throwNext = new java.util.concurrent.atomic.AtomicInteger();
        try (AutoCloseable restore = capture(flakyHandler(logs, throwNext), java.util.logging.Level.INFO)) {
            failure.set(new IllegalStateException("주입"));
            agent.sampleOnce();
            agent.sampleOnce();
            failure.set(null);
            throwNext.set(1);
            agent.sampleOnce(); // 회복 INFO 출력 실패
            failure.set(new IllegalStateException("주입"));
            agent.sampleOnce();
            failure.set(null);
            agent.sampleOnce();
            agent.sampleOnce(); // 낡은 회복 줄("2회")이 뒤늦게 나오지 않는다
        }
        assertThat(logs).containsExactly(
                "WARNING 수집원 실패: a (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다",
                "WARNING 수집원 실패: a (IllegalStateException). 같은 실패가 이어지는 동안은 다시 경고하지 않는다",
                "INFO 수집원 회복: a (IllegalStateException 1회 연속 실패 뒤)");
    }

    @Test
    void 출력에_실패한_줄은_그_사이_만든_새_줄을_덮지_않는다() throws Exception {
        // architect PR #155 N3: 출력 중 다른 호출이 같은 수집원의 새 줄을 내면, 늦게 실패한 옛 줄을 다시 내지 않는다.
        // 수집 스레드와 직접 호출이 겹치는 경우를 핸들러 안에서 sampleOnce를 다시 불러 결정적으로 만든다
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        MonitorAgent agent = new MonitorAgent(CLOCK, List.of(failing("a", failure)), List.of(), 10, 3);
        List<String> logs = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean nestOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getMessage().startsWith("수집원 실패: a") && nestOnce.getAndSet(false)) {
                    failure.set(null);
                    agent.sampleOnce(); // 그 사이 회복: 회복 INFO가 먼저 나간다
                    throw new IllegalStateException("핸들러 예외 주입");
                }
                logs.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        try (AutoCloseable restore = capture(handler, java.util.logging.Level.INFO)) {
            failure.set(new IllegalStateException("주입"));
            agent.sampleOnce();
            agent.sampleOnce();
            agent.sampleOnce();
        }
        assertThat(logs).containsExactly("INFO 수집원 회복: a (IllegalStateException 1회 연속 실패 뒤)");
    }

    @Test
    void 리스너_실패_로그가_던져도_남은_리스너와_스냅샷_반환을_건너뛰지_않는다() throws Exception {
        // architect PR #155 N4
        MonitorAgent agent = agent(10);
        agent.addListener((s, e) -> {
            throw new IllegalStateException("리스너 실패 주입");
        });
        List<MetricSnapshot> heard = new ArrayList<>();
        agent.addListener((s, e) -> heard.add(s));
        java.util.concurrent.atomic.AtomicInteger throwNext = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        try (AutoCloseable restore = capture(flakyHandler(new ArrayList<>(), throwNext), java.util.logging.Level.INFO)) {
            MetricSnapshot snap = agent.sampleOnce();
            assertThat(heard).containsExactly(snap);
        }
    }

    @Test
    void 주기_수집_실패_로그가_던져도_주기_수집이_이어진다() throws Exception {
        // architect PR #155 N4: sampleQuietly의 catch 안 로그가 던지면 예약 작업이 취소돼 수집이 멈췄다
        java.util.concurrent.atomic.AtomicInteger clockFailures = new java.util.concurrent.atomic.AtomicInteger(3);
        Clock broken = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                if (clockFailures.getAndDecrement() > 0) {
                    throw new IllegalStateException("시계 예외 주입"); // sampleOnce가 던지게 한다
                }
                return CLOCK.instant();
            }
        };
        java.util.concurrent.atomic.AtomicInteger throwNext = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        try (AutoCloseable restore = capture(flakyHandler(new ArrayList<>(), throwNext), java.util.logging.Level.INFO);
                MonitorAgent agent = new MonitorAgent(broken, List.of(source), List.of(), 10, 3)) {
            CountDownLatch sampled = new CountDownLatch(2);
            agent.addListener((s, e) -> sampled.countDown());
            agent.start(Duration.ofMillis(10));
            assertThat(sampled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(clockFailures.get()).isNegative();
        }
    }

    @Test
    void 수집원_id가_겹치면_만들지_않는다() {
        // 같은 id의 실패·성공 수집원이 섞이면 주기마다 WARN·INFO가 번갈아 남는다(PR #119 QA R1)
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        assertThatThrownBy(() -> new MonitorAgent(CLOCK, List.of(failing("system", failure), source,
                failing("system", failure)), List.of(), 10, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수집원 id가 겹칩니다: system");
        assertThatThrownBy(() -> new MonitorAgent(CLOCK, List.of(failing(null, failure)), List.of(), 10, 3))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 잘못된_주기와_기록_크기는_거절한다() {
        assertThatThrownBy(() -> agent(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agent(1).start(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
