package com.autotrade.app.lab.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.monitor.Threshold;
import com.autotrade.monitor.Threshold.Direction;
import com.autotrade.monitorsources.MetricNames;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitorSettingsTest {

    @Test
    void 임계_덮어쓰기_문자열을_읽는다() {
        assertThat(MonitorSettings.parseThresholds(" cpu.system=ABOVE:70:90:3 ; disk.free-gb=below:100:30:1;", MetricNames.ALL)).containsExactly(
                new Threshold("cpu.system", Direction.ABOVE, 70, 90, 3),
                new Threshold("disk.free-gb", Direction.BELOW, 100, 30, 1));
        assertThat(MonitorSettings.parseThresholds("", MetricNames.ALL)).isEmpty();
        assertThat(MonitorSettings.parseThresholds(null, MetricNames.ALL)).isEmpty();
    }

    @Test
    void 틀린_임계는_기동을_막는다() {
        for (String bad : List.of("cpu.system=ABOVE:70:90", "=ABOVE:1:2:1", "cpu.system=UP:70:90:3", "cpu.system=ABOVE:x:90:3",
                "cpu.system=ABOVE:95:90:3", "cpu.system=ABOVE:70:90:0", "cpu.system",
                "cpu.system=ABOVE:70:90:3;cpu.system=ABOVE:60:90:3")) {
            assertThatThrownBy(() -> MonitorSettings.parseThresholds(bad, MetricNames.ALL)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("autotrade.lab.monitor.thresholds");
        }
    }

    @Test
    void 모르는_지표_이름은_기동을_막는다() {
        // 오타(cpu.sytem)를 받으면 아무 효과 없는 새 임계가 되고 기본 cpu.system 임계는 그대로 남는다(architect PR #114 R2)
        assertThatThrownBy(() -> MonitorSettings.parseThresholds("cpu.sytem=ABOVE:70:90:3", MetricNames.ALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("모르는 지표 이름").hasMessageContaining("cpu.sytem").hasMessageContaining("cpu.system");
        // 대상까지 쓴 이름도 지표 이름이 아니다
        assertThatThrownBy(() -> MonitorSettings.parseThresholds("disk.free-gb:C:\\=BELOW:50:20:1", MetricNames.ALL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 주기와_기록_수_범위() {
        assertThatThrownBy(() -> new MonitorSettings(true, 0, 10, Set.of(), List.of())).hasMessageContaining("interval-seconds");
        assertThatThrownBy(() -> new MonitorSettings(true, 3601, 10, Set.of(), List.of())).hasMessageContaining("interval-seconds");
        assertThatThrownBy(() -> new MonitorSettings(true, 5, 0, Set.of(), List.of())).hasMessageContaining("history-size");
        assertThatThrownBy(() -> new MonitorSettings(true, 5, 10_001, Set.of(), List.of())).hasMessageContaining("history-size");
    }

    @Test
    void 드라이브_목록이_비면_모두_고르고_아니면_대소문자를_무시해_고른다() {
        assertThat(MonitorSettings.parseDisks(" C:\\ , ,d:\\")).containsExactlyInAnyOrder("C:\\", "d:\\");
        MonitorSettings all = new MonitorSettings(true, 5, 10, Set.of(), List.of());
        assertThat(all.diskFilter().test("/")).isTrue();
        MonitorSettings some = new MonitorSettings(true, 5, 10, MonitorSettings.parseDisks("c:\\"), List.of());
        assertThat(some.diskFilter().test("C:\\")).isTrue();
        assertThat(some.diskFilter().test("D:\\")).isFalse();
    }
}
