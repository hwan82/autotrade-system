package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.sim.walkforward.WalkForwardWindows.Window;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WalkForwardWindowsTest {

    @Test
    void 학습_3년_검증_1년을_1년씩_굴리고_검증이_빠짐_겹침_없이_이어진다() {
        List<Window> w = WalkForwardWindows.compute(LocalDate.of(2022, 4, 1), 3, 1, 4);
        assertThat(w).hasSize(4);
        assertThat(w.get(0).trainFrom()).isEqualTo(LocalDate.of(2019, 4, 1));
        assertThat(w.get(0).trainTo()).isEqualTo(LocalDate.of(2022, 3, 31));
        assertThat(w.get(0).testFrom()).isEqualTo(LocalDate.of(2022, 4, 1));
        assertThat(w.get(0).testTo()).isEqualTo(LocalDate.of(2023, 3, 31));
        assertThat(w.get(3).testFrom()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(w.get(3).testTo()).isEqualTo(LocalDate.of(2026, 3, 31));
        for (int k = 1; k < w.size(); k++) {
            assertThat(w.get(k).testFrom()).isEqualTo(w.get(k - 1).testTo().plusDays(1));
            assertThat(w.get(k).trainTo()).isEqualTo(w.get(k).testFrom().minusDays(1));
        }
        assertThat(w.get(2).number()).isEqualTo(3);
    }

    @Test
    void 윤년_2월_29일_시작도_빠짐_겹침_없이_이어진다() {
        List<Window> w = WalkForwardWindows.compute(LocalDate.of(2020, 2, 29), 3, 1, 5);
        assertThat(w.get(0).trainFrom()).isEqualTo(LocalDate.of(2017, 2, 28));
        assertThat(w.get(1).testFrom()).isEqualTo(LocalDate.of(2021, 2, 28));
        assertThat(w.get(4).testFrom()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(w.get(4).testTo()).isEqualTo(LocalDate.of(2025, 2, 27));
        for (int k = 1; k < w.size(); k++) {
            assertThat(w.get(k).testFrom()).isEqualTo(w.get(k - 1).testTo().plusDays(1));
        }
    }

    @Test
    void 경계가_휴장일_주말이어도_달력_날짜로_이어진다() {
        List<Window> w = WalkForwardWindows.compute(LocalDate.of(2023, 1, 1), 3, 1, 3); // 일요일 시작
        assertThat(w.get(0).trainTo()).isEqualTo(LocalDate.of(2022, 12, 31));
        assertThat(w.get(1).testFrom()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(w.get(0).testTo().plusDays(1)).isEqualTo(w.get(1).testFrom());
    }

    @Test
    void 구간이_3개_미만이면_거부한다() {
        assertThatThrownBy(() -> WalkForwardWindows.compute(LocalDate.of(2022, 4, 1), 3, 1, 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3개 이상");
    }

    @Test
    void 스냅샷_범위_밖이면_거부한다() {
        List<Window> w = WalkForwardWindows.compute(LocalDate.of(2022, 4, 1), 3, 1, 4);
        WalkForwardWindows.checkCoverage(w, LocalDate.of(2019, 4, 1), LocalDate.of(2026, 3, 31)); // 경계 포함은 통과
        assertThatThrownBy(() -> WalkForwardWindows.checkCoverage(w, LocalDate.of(2019, 4, 2), LocalDate.of(2026, 3, 31)))
                .hasMessageContaining("스냅샷 범위 밖");
        assertThatThrownBy(() -> WalkForwardWindows.checkCoverage(w, LocalDate.of(2010, 1, 4), LocalDate.of(2026, 3, 30)))
                .hasMessageContaining("스냅샷 범위 밖");
    }
}
