package com.autotrade.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.monitor.Threshold.Direction;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThresholdEvaluatorTest {

    private static final Instant T = Instant.parse("2026-10-15T00:00:00Z");

    private final ThresholdEvaluator evaluator = new ThresholdEvaluator(List.of(
            new Threshold("cpu.system", Direction.ABOVE, 85, 95, 3),
            new Threshold("disk.free-gb", Direction.BELOW, 50, 20, 1)));

    private List<ThresholdEvent> feed(String name, double value, String unit) {
        return evaluator.evaluate(T, List.of(new Reading(name, value, unit)));
    }

    @Test
    void 같은_판정이_sustain번_이어져야_수준이_바뀐다() {
        assertThat(feed("cpu.system", 90, "%")).isEmpty();
        assertThat(feed("cpu.system", 91, "%")).isEmpty();
        List<ThresholdEvent> events = feed("cpu.system", 92, "%");
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo(Level.OK);
            assertThat(e.to()).isEqualTo(Level.WARN);
            assertThat(e.value()).isEqualTo(92);
            assertThat(e.limit()).isEqualTo(85);
            assertThat(e.escalation()).isTrue();
        });
        assertThat(evaluator.levels()).containsEntry("cpu.system", Level.WARN);
        // 이미 WARN이면 같은 판정은 이벤트를 내지 않는다
        assertThat(feed("cpu.system", 93, "%")).isEmpty();
    }

    @Test
    void 한_번_튀는_값은_알리지_않고_연속_횟수를_새로_센다() {
        feed("cpu.system", 99, "%");
        feed("cpu.system", 99, "%");
        assertThat(feed("cpu.system", 10, "%")).isEmpty(); // 끊김
        feed("cpu.system", 99, "%");
        feed("cpu.system", 99, "%");
        assertThat(evaluator.levels()).containsEntry("cpu.system", Level.OK);
        assertThat(feed("cpu.system", 99, "%")).singleElement()
                .satisfies(e -> assertThat(e.to()).isEqualTo(Level.CRITICAL)); // OK에서 바로 CRITICAL
    }

    @Test
    void 판정이_중간에_바뀌면_새_판정으로_다시_센다() {
        feed("cpu.system", 90, "%");
        feed("cpu.system", 90, "%");
        assertThat(feed("cpu.system", 99, "%")).isEmpty();
        assertThat(feed("cpu.system", 99, "%")).isEmpty();
        assertThat(feed("cpu.system", 99, "%")).singleElement()
                .satisfies(e -> assertThat(e.to()).isEqualTo(Level.CRITICAL));
    }

    @Test
    void 돌아올_때도_이벤트를_내고_경계는_떠난_수준의_경계다() {
        for (int i = 0; i < 3; i++) {
            feed("cpu.system", 99, "%");
        }
        feed("cpu.system", 50, "%");
        feed("cpu.system", 50, "%");
        assertThat(feed("cpu.system", 50, "%")).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo(Level.CRITICAL);
            assertThat(e.to()).isEqualTo(Level.OK);
            assertThat(e.limit()).isEqualTo(95);
            assertThat(e.escalation()).isFalse();
        });
    }

    @Test
    void 아래쪽_임계는_대상별로_따로_판정한다() {
        List<ThresholdEvent> events = evaluator.evaluate(T, List.of(
                new Reading("disk.free-gb:D:\\", 10, "GB"),
                new Reading("disk.free-gb:C:\\", 40, "GB")));
        assertThat(events).extracting(ThresholdEvent::reading, ThresholdEvent::to).containsExactly(
                org.assertj.core.groups.Tuple.tuple("disk.free-gb:C:\\", Level.WARN),
                org.assertj.core.groups.Tuple.tuple("disk.free-gb:D:\\", Level.CRITICAL));
    }

    @Test
    void 임계가_없는_지표는_수준을_두지_않는다() {
        assertThat(feed("net.rx", 1e9, "B/s")).isEmpty();
        assertThat(evaluator.levels()).doesNotContainKey("net.rx");
    }

    @Test
    void 잘못된_임계는_거절한다() {
        assertThatThrownBy(() -> new Threshold("cpu", Direction.ABOVE, 90, 80, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Threshold("disk", Direction.BELOW, 20, 50, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Threshold("cpu", Direction.ABOVE, 80, 90, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Threshold("disk:C", Direction.ABOVE, 80, 90, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Threshold("cpu", Direction.ABOVE, Double.NaN, 90, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ThresholdEvaluator(List.of(
                new Threshold("cpu", Direction.ABOVE, 80, 90, 1), new Threshold("cpu", Direction.ABOVE, 70, 90, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reading("cpu", Double.NaN, "%")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reading("cpu", Double.POSITIVE_INFINITY, "%")).isInstanceOf(IllegalArgumentException.class);
    }
}
