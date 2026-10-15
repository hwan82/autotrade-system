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
        try (MonitorService service = new MonitorService(settings, agent)) {
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
        try (MonitorService service = new MonitorService(settings, agent)) {
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
    void 꺼져_있으면_수집하지_않고_실시간_연결도_열지_않는다() {
        MonitorSettings off = new MonitorSettings(false, 5, 10, Set.of(), List.of());
        try (MonitorService service = new MonitorService(off, null)) {
            service.start();
            assertThat(service.view().enabled()).isFalse();
            assertThat(service.view().latest()).isNull();
            assertThat(service.openStream()).isEmpty();
        }
    }
}
