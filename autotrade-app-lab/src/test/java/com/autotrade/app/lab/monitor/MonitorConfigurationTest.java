package com.autotrade.app.lab.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 모니터링 설정 오류는 실험실 기동을 막지 않고 모니터링만 사용 불가로 둔다(QA PR #114 R1). */
class MonitorConfigurationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void 올바른_설정은_그대로_읽는다() {
        MonitorConfiguration.Loaded loaded = MonitorConfiguration.load(" TRUE ", "10", "100", "C:\\", "cpu.system=ABOVE:70:90:3");
        assertThat(loaded.problem()).isNull();
        assertThat(loaded.settings().enabled()).isTrue();
        assertThat(loaded.settings().intervalSeconds()).isEqualTo(10);
        assertThat(loaded.settings().historySize()).isEqualTo(100);
        assertThat(loaded.settings().thresholds()).singleElement().satisfies(t -> assertThat(t.metric()).isEqualTo("cpu.system"));
    }

    @Test
    void 잘못된_설정은_모니터링만_끄고_이유를_남긴다() {
        String[][] bad = {
            {"true", "5", "720", "", "cpu.sytem=ABOVE:70:90:3"}, // 지표 이름 오타(QA P1)
            {"true", "0", "720", "", ""}, // 주기 범위(QA P1b)
            {"true", "abc", "720", "", ""},
            {"true", "5", "0", "", ""},
            {"yes", "5", "720", "", ""},
            {"true", "5", "720", "", "cpu.system=ABOVE:90:70"},
        };
        for (String[] b : bad) {
            MonitorConfiguration.Loaded loaded = MonitorConfiguration.load(b[0], b[1], b[2], b[3], b[4]);
            assertThat(loaded.settings().enabled()).as(String.join("|", b)).isFalse();
            assertThat(loaded.problem()).as(String.join("|", b)).startsWith("모니터링 설정이 잘못됐습니다");
        }
        assertThat(MonitorConfiguration.load("true", "5", "720", "", "cpu.sytem=ABOVE:70:90:3").problem()).contains("cpu.sytem");
    }

    @Test
    void 설정이_잘못되면_수집원을_만들지_않고_화면에_이유를_준다() {
        MonitorConfiguration.Loaded loaded = MonitorConfiguration.load("true", "0", "720", "", "");
        MonitorAgentFactory factory = s -> {
            throw new AssertionError("설정이 잘못되면 수집원을 만들지 않는다");
        };
        try (MonitorService service = new MonitorConfiguration().monitorService(loaded, factory, CLOCK)) {
            service.start();
            assertThat(service.view().enabled()).isFalse();
            assertThat(service.view().problem()).contains("interval-seconds");
            assertThat(service.openStream()).isEmpty();
        }
    }
}
