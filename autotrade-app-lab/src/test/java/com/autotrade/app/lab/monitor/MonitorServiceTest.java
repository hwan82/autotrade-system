package com.autotrade.app.lab.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.monitor.Level;
import com.autotrade.monitor.MetricSource;
import com.autotrade.monitor.MonitorAgent;
import com.autotrade.monitor.Reading;
import com.autotrade.monitor.Threshold;
import com.autotrade.monitor.Threshold.Direction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitorServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T01:00:00Z"), ZoneOffset.UTC);
    private final MonitorSettings settings = new MonitorSettings(true, 5, 10, Set.of(), List.of());

    private double disk = 100;

    private MonitorAgent agent() {
        MetricSource source = new MetricSource() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public List<Reading> read() {
                return List.of(new Reading("disk.free-gb:C:\\", disk, "GB"), new Reading("net.rx", 1234.5678, "B/s"));
            }
        };
        return new MonitorAgent(CLOCK, List.of(source), List.of(new Threshold("disk.free-gb", Direction.BELOW, 50, 20, 1)),
                10, 10);
    }

    @Test
    void 화면_상태는_현재_값_대상_수준_기록_임계_이벤트를_담는다() {
        MonitorAgent agent = agent();
        try (MonitorService service = new MonitorService(settings, agent, CLOCK, null)) {
            assertThat(service.view().latest()).isNull(); // 첫 수집 전
            agent.sampleOnce();
            disk = 10;
            agent.sampleOnce();

            MonitorService.View view = service.view();
            assertThat(view.enabled()).isTrue();
            assertThat(view.latest().readings()).extracting(MonitorService.ReadingView::name, MonitorService.ReadingView::metric,
                    MonitorService.ReadingView::target, MonitorService.ReadingView::value, MonitorService.ReadingView::level)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("disk.free-gb:C:\\", "disk.free-gb", "C:\\", 10.0, Level.CRITICAL),
                            org.assertj.core.groups.Tuple.tuple("net.rx", "net.rx", "", 1234.57, null));
            assertThat(view.history()).hasSize(2);
            assertThat(view.history().get(0).values()).containsEntry("disk.free-gb:C:\\", 100.0);
            assertThat(view.thresholds()).singleElement().extracting(MonitorService.ThresholdView::direction).isEqualTo("BELOW");
            assertThat(view.events()).singleElement().satisfies(e -> assertThat(e.to()).isEqualTo(Level.CRITICAL));
        }
    }

    @Test
    void 실시간_연결은_정한_수까지만_열고_닫히면_자리를_돌려준다() {
        MonitorAgent agent = agent();
        try (MonitorService service = new MonitorService(settings, agent, CLOCK, null)) {
            for (int i = 0; i < MonitorService.MAX_STREAMS; i++) {
                assertThat(service.openStream()).isPresent();
            }
            assertThat(service.openStream()).isEmpty();
            // 연결이 열려 있어도 수집과 보내기가 막히지 않는다(처리기 연결 전 보내기는 쌓아 둔다)
            agent.sampleOnce();
            assertThat(service.openStreams()).isEqualTo(MonitorService.MAX_STREAMS);
        }
    }

    @Test
    void 마지막_표본이_주기의_3배보다_오래되면_수집_멈춤이다() {
        MonitorAgent agent = agent();
        try (MonitorService service = new MonitorService(settings, agent, CLOCK, null)) {
            assertThat(service.view().stale()).isFalse(); // 첫 수집 전은 멈춤이 아니다
            agent.sampleOnce();
            assertThat(service.view().stale()).isFalse();
            assertThat(service.stale(java.util.Optional.of(CLOCK.instant().minusSeconds(15)))).isFalse(); // 5초 x 3 = 경계
            assertThat(service.stale(java.util.Optional.of(CLOCK.instant().minusSeconds(16)))).isTrue();
        }
    }

    @Test
    void 첫_표본이_수집_시작부터_주기의_3배_넘게_없으면_수집_멈춤이다() {
        Instant start = CLOCK.instant();
        Instant[] now = {start};
        Clock clock = new Clock() {
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
                return now[0];
            }
        };
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        MetricSource stuck = new MetricSource() {
            @Override
            public String id() {
                return "stuck";
            }

            @Override
            public List<Reading> read() {
                // 첫 read가 막힌다(QA PR #114 P2: Windows WMI 첫 초기화 정지 등)
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }
        };
        MonitorAgent agent = new MonitorAgent(clock, List.of(stuck), List.of(), 10, 10);
        MonitorSettings hourly = new MonitorSettings(true, 3600, 10, Set.of(), List.of());
        try (MonitorService service = new MonitorService(hourly, agent, clock, null)) {
            assertThat(service.view().stale()).isFalse(); // 시작 전
            assertThat(service.view().startedAt()).isNull();
            service.start();
            assertThat(service.view().startedAt()).isEqualTo(start);
            now[0] = start.plusSeconds(3 * 3600);
            assertThat(service.view().latest()).isNull();
            assertThat(service.view().stale()).isFalse(); // 경계
            now[0] = start.plusSeconds(3 * 3600 + 1);
            assertThat(service.view().stale()).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void 종료_첫_단계에서_열린_실시간_연결을_닫고_새_연결을_받지_않는다() {
        MonitorAgent agent = agent();
        try (MonitorService service = new MonitorService(settings, agent, CLOCK, null)) {
            assertThat(service.openStream()).isPresent();
            service.closeStreams();
            assertThat(service.openStreams()).isZero();
            assertThat(service.openStream()).isEmpty();
        }
    }

    @Test
    void 수집원을_만들지_못하면_실험실은_뜨고_모니터링만_사용_불가다() {
        MonitorService service = MonitorConfiguration.create(settings, s -> {
            throw new UnsatisfiedLinkError("jnidispatch 적재 실패 주입");
        }, CLOCK);
        try (service) {
            service.start();
            MonitorService.View view = service.view();
            assertThat(view.enabled()).isFalse();
            assertThat(view.problem()).contains("UnsatisfiedLinkError");
            assertThat(service.openStream()).isEmpty();
        }
    }

    @Test
    void 꺼져_있으면_수집하지_않고_실시간_연결도_열지_않는다() {
        MonitorSettings off = new MonitorSettings(false, 5, 10, Set.of(), List.of());
        try (MonitorService service = new MonitorService(off, null, CLOCK, null)) {
            service.start();
            assertThat(service.view().enabled()).isFalse();
            assertThat(service.view().problem()).isNull();
            assertThat(service.view().latest()).isNull();
            assertThat(service.openStream()).isEmpty();
        }
    }
}
