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
import org.junit.jupiter.api.Test;

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
    void 잘못된_주기와_기록_크기는_거절한다() {
        assertThatThrownBy(() -> agent(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agent(1).start(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
