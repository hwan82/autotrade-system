package com.autotrade.monitorsources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.monitor.MetricSnapshot;
import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitor.Reading;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** OSHI 네이티브 적재 실패(LinkageError)가 붙인 프로세스의 기동과 주기 수집을 멈추지 않는다(architect PR #114 R3). */
class SystemSourceFailureTest {

    private static SystemSource broken(AtomicInteger calls) {
        return new SystemSource(Clock.systemUTC(), mount -> true, () -> {
            calls.incrementAndGet();
            throw new UnsatisfiedLinkError("jnidispatch 적재 실패 주입");
        });
    }

    @Test
    void 만들_때는_OSHI를_건드리지_않고_읽을_때_LinkageError를_감싸_던진다() {
        AtomicInteger calls = new AtomicInteger();
        SystemSource source = broken(calls);
        assertThat(calls).hasValue(0);
        assertThatThrownBy(source::read).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnsatisfiedLinkError").hasCauseInstanceOf(UnsatisfiedLinkError.class);
        // 다음 호출에서 다시 시도한다(초기화가 반쯤 끝난 상태로 남지 않음)
        assertThatThrownBy(source::read).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void OSHI가_실패해도_JVM_지표는_나오고_주기_수집이_이어진다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (MonitorAgent agent = new MonitorAgent(Clock.systemUTC(), List.of(broken(calls), new JvmSource()),
                StandardSources.defaultThresholds(), 10, 10)) {
            CountDownLatch three = new CountDownLatch(3);
            agent.addListener((s, e) -> three.countDown());
            agent.start(Duration.ofMillis(20));
            assertThat(three.await(10, TimeUnit.SECONDS)).isTrue();
            MetricSnapshot last = agent.latest().orElseThrow();
            assertThat(last.failedSources()).containsExactly("system");
            assertThat(last.readings()).extracting(Reading::metric).contains(MetricNames.JVM_THREADS);
            assertThat(calls.get()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void 지표_이름_목록은_수집원이_내는_이름을_모두_담는다() {
        try (MonitorAgent agent = StandardSources.agent(Clock.systemUTC(), mount -> true, List.of(), 10, 10)) {
            agent.sampleOnce();
            List<String> seen = agent.sampleOnce().readings().stream().map(Reading::metric).distinct().toList();
            assertThat(MetricNames.ALL).containsAll(seen);
        }
    }

}
