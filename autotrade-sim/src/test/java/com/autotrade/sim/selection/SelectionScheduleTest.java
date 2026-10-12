package com.autotrade.sim.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionInvalidReason;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** 날짜별 선정 표: 공급자 호출 횟수와 ADR-007 2.6 판정 중 메모리 결과에 해당하는 사유. */
class SelectionScheduleTest {

    private static final Symbol A = Symbol.krx("005930");
    private static final Symbol B = Symbol.krx("000660");
    private static final Symbol C = Symbol.krx("035420");
    private static final String H = "c".repeat(64);
    // 2026-10-05(월) ~ 10-09(금), 10-12(월)
    private static final List<LocalDate> DAYS = List.of(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6),
            LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12));

    @Test
    void 주간_결과는_적용_범위_안에서_다시_부르지_않는다() {
        SelectionProfile weekly = selection("weekly", "POST_CLOSE_WEEKLY", "2");
        List<LocalDate> calls = new ArrayList<>();
        SelectionSource source = (s, d) -> {
            calls.add(d);
            LocalDate monday = d.minusDays(d.getDayOfWeek().getValue() - 1);
            return new DailySelection.Selected(s.selectionHash(), H, monday, monday.plusDays(4), new TreeSet<>(List.of(A)));
        };
        // 수요일부터 시작해도 그 주 결과 하나를 쓴다
        SelectionSchedule schedule = SelectionSchedule.compute(List.of(combo(weekly)), DAYS.subList(2, 6), source);
        assertThat(calls).containsExactly(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 12));
        assertThat(schedule.days(weekly.selectionHash()).values()).allSatisfy(d -> assertThat(d.isValid()).isTrue());
        assertThat(schedule.day(weekly.selectionHash(), LocalDate.of(2026, 10, 8)).allowsEntry(A)).isTrue();
        assertThat(schedule.day(weekly.selectionHash(), LocalDate.of(2026, 10, 8)).allowsEntry(B)).isFalse();
    }

    @Test
    void 결과의_형식_해시_적용_범위를_판정한다() {
        SelectionProfile daily = selection("daily", "POST_CLOSE_DAILY", "2");
        String sh = daily.selectionHash();
        LocalDate d1 = DAYS.get(0);
        assertThat(judge(daily, (s, d) -> new DailySelection.Selected(sh, H, d, d, new TreeSet<>(List.of(A, B, C)))))
                .as("max-symbols 초과").isEqualTo(SelectionInvalidReason.MALFORMED);
        assertThat(judge(daily, (s, d) -> new DailySelection.Selected(sh, "X".repeat(64), d, d, new TreeSet<>())))
                .as("결과 해시 형식").isEqualTo(SelectionInvalidReason.MALFORMED);
        assertThat(judge(daily, (s, d) -> new DailySelection.Selected(sh, H, d, d.plusDays(1), new TreeSet<>())))
                .as("매일 갱신인데 범위가 하루가 아님").isEqualTo(SelectionInvalidReason.MALFORMED);
        assertThat(judge(daily, (s, d) -> new DailySelection.Selected(H, H, d, d, new TreeSet<>())))
                .isEqualTo(SelectionInvalidReason.HASH_MISMATCH);
        assertThat(judge(daily, (s, d) -> new DailySelection.Selected(sh, H, d.plusDays(1), d.plusDays(1),
                new TreeSet<>()))).isEqualTo(SelectionInvalidReason.NOT_YET);
        assertThat(judge(daily, (s, d) -> new DailySelection.Selected(sh, H, d.minusDays(1), d.minusDays(1),
                new TreeSet<>()))).isEqualTo(SelectionInvalidReason.EXPIRED);
        assertThat(judge(daily, (s, d) -> null)).isEqualTo(SelectionInvalidReason.MISSING);
        assertThat(judge(daily, (s, d) -> new DailySelection.Unavailable(SelectionInvalidReason.STALE_INPUT, "")))
                .isEqualTo(SelectionInvalidReason.STALE_INPUT);
        assertThat(judge(daily, (s, d) -> {
            throw new IllegalStateException("x");
        })).isEqualTo(SelectionInvalidReason.PRODUCER_FAILED);
        // 표에 없는 날
        SelectionSchedule empty = SelectionSchedule.compute(List.of(combo(daily)), List.of(), (s, d) -> null);
        assertThat(empty.day(sh, d1).reason()).isEqualTo(SelectionInvalidReason.MISSING);
        assertThat(empty.day(sh, d1).allowsEntry(A)).isFalse();
    }

    @Test
    void 선정_없는_조합은_공급자를_부르지_않는다() {
        List<LocalDate> calls = new ArrayList<>();
        SelectionSchedule.compute(List.of(Combination.of(strategy(TradingStyle.SWING))), DAYS, (s, d) -> {
            calls.add(d);
            return null;
        });
        assertThat(calls).isEmpty();
    }

    private static SelectionInvalidReason judge(SelectionProfile s, SelectionSource source) {
        SelectionDay day = SelectionSchedule.compute(List.of(combo(s)), DAYS.subList(0, 1), source)
                .day(s.selectionHash(), DAYS.get(0));
        return day.reason();
    }

    private static Combination combo(SelectionProfile s) {
        return Combination.of(s, strategy(s.style()));
    }

    private static StrategyProfile strategy(TradingStyle style) {
        return new StrategyProfile("p", 1, Market.KRX, style, new TreeMap<>());
    }

    private static SelectionProfile selection(String id, String refresh, String max) {
        return new SelectionProfile(id, 1, Market.KRX, TradingStyle.SWING,
                new TreeMap<>(Map.of("hypothesis", "h", "refresh", refresh, "max-symbols", max)));
    }
}
