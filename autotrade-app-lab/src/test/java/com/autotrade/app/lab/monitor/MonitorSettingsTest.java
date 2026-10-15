package com.autotrade.app.lab.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.monitor.Threshold;
import com.autotrade.monitor.Threshold.Direction;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitorSettingsTest {

    @Test
    void 임계_덮어쓰기_문자열을_읽는다() {
        assertThat(MonitorSettings.parseThresholds(" cpu.system=ABOVE:70:90:3 ; disk.free-gb=below:100:30:1;")).containsExactly(
                new Threshold("cpu.system", Direction.ABOVE, 70, 90, 3),
                new Threshold("disk.free-gb", Direction.BELOW, 100, 30, 1));
        assertThat(MonitorSettings.parseThresholds("")).isEmpty();
        assertThat(MonitorSettings.parseThresholds(null)).isEmpty();
    }

    @Test
    void 틀린_임계는_기동을_막는다() {
        for (String bad : List.of("cpu.system=ABOVE:70:90", "=ABOVE:1:2:1", "cpu.system=UP:70:90:3", "cpu.system=ABOVE:x:90:3",
                "cpu.system=ABOVE:95:90:3", "cpu.system=ABOVE:70:90:0", "cpu.system",
                "cpu.system=ABOVE:70:90:3;cpu.system=ABOVE:60:90:3")) {
            assertThatThrownBy(() -> MonitorSettings.parseThresholds(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("autotrade.lab.monitor.thresholds");
        }
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
