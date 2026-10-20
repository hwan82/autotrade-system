package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollectorReportsTest {

    private static final ExchangeCalendar CAL = KrxFixtures.calendar();

    private static KrxDailySnapshotBuilder.Stats stats(List<LocalDate> empty, List<KrxDailySnapshotBuilder.BoardGap> gaps,
            List<KrxDailySnapshotBuilder.AdjustmentDay> mass, Map<LocalDate, Integer> after) {
        return new KrxDailySnapshotBuilder.Stats(1, 1, 0, 0, 0, 0, 0, 0, LocalDate.of(2024, 1, 2), LocalDate.of(2026, 10, 8),
                empty, gaps, mass, after);
    }

    @Test
    void 빈_평일을_연도별로_세고_캘린더로_나누고_다음_날_이벤트를_붙인다() {
        LocalDate fake = LocalDate.of(2024, 4, 10);
        var s = stats(List.of(LocalDate.of(2024, 3, 1), fake, LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24)), List.of(),
                List.of(), Map.of(LocalDate.of(2024, 3, 1), 0, fake, 1700, LocalDate.of(2026, 9, 23), 2, LocalDate.of(2026, 9, 24), 0));
        String md = CollectorReports.gapSections(s, CAL, Set.of());
        // 칸: 연도, 빈 평일, 캘린더 휴장일, 거래일(오류), 범위 밖, 게시 전, 다음 날 이벤트 30 이상, 날짜
        assertThat(md).contains("| 2024 | 2 | 0 | 0 | 2 | 0 | 1 | 03-01[0], 04-10[1700] |")
                .contains("| 2026 | 2 | 1 | 1 | 0 | 0 | 0 | 09-23[2](거래일!), 09-24[0] |")
                .contains("## 시장 하나만 빈 날\n\n").contains("## 같은 날 여러 종목 수정 이벤트(경고)");
        var w = CollectorReports.warnings(s, CAL, Set.of());
        assertThat(w.emptyTradingDays()).isEqualTo(1);
        assertThat(w.any()).isTrue();
    }

    @Test
    void 게시_전으로_넘긴_최근_빈_날은_오류로_세지_않는다() {
        // 서울 18:30 매일 실행에서 KRX가 오늘 자료를 아직 안 올린 경우(PR #141 architect F1)
        LocalDate today = LocalDate.of(2026, 10, 8);
        var s = stats(List.of(today), List.of(), List.of(), Map.of());
        String md = CollectorReports.gapSections(s, CAL, Set.of(today));
        assertThat(md).contains("| 2026 | 1 | 0 | 0 | 0 | 1 | 0 | 10-08[-](게시 전) |");
        assertThat(CollectorReports.warnings(s, CAL, Set.of(today)).any()).isFalse();
        assertThat(CollectorReports.warnings(s, CAL, Set.of()).emptyTradingDays()).isEqualTo(1); // 캐시된 날이면 오류
    }

    @Test
    void 시장_공백과_수정_이벤트_몰린_날을_직전_공백_전부와_함께_적는다() {
        LocalDate mon = LocalDate.of(2024, 3, 4);
        var s = stats(List.of(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1)),
                List.of(new KrxDailySnapshotBuilder.BoardGap(mon, KrxBoard.KOSDAQ)),
                List.of(new KrxDailySnapshotBuilder.AdjustmentDay(LocalDate.of(2024, 3, 5), 1700, 2600, List.of(mon)),
                        new KrxDailySnapshotBuilder.AdjustmentDay(mon, 35, 900,
                                List.of(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1)))),
                Map.of());
        String md = CollectorReports.gapSections(s, CAL, Set.of());
        assertThat(md).contains("| 2024-03-04 | KOSDAQ |")
                .contains("| 2024-03-05 | 1700 | 2600 | 65.4% | 2024-03-04 (KOSDAQ 공백) |")
                .contains("| 2024-03-04 | 35 | 900 | 3.9% | 2024-02-29 (빈 평일, 캘린더 범위 밖(대조 필요)), "
                        + "2024-03-01 (빈 평일, 캘린더 범위 밖(대조 필요)) |");
        assertThat(CollectorReports.warnings(s, CAL, Set.of())).isEqualTo(new CollectorReports.DailyWarnings(0, 1, 2));
    }

    @Test
    void 공백이_없으면_없음으로_적고_경고도_없다() {
        var s = stats(List.of(), List.of(), List.of(), Map.of());
        assertThat(CollectorReports.gapSections(s, CAL, Set.of())).contains("| - | 0 | 0 | 0 | 0 | 0 | 0 | |").contains("없음\n");
        assertThat(CollectorReports.warnings(s, CAL, Set.of()).any()).isFalse();
    }

    @Test
    void 실제_거래대금_칸은_행_수_결측_범위_밖_비율과_예시를_적는다() {
        String md = CollectorReports.turnoverSection(new KrxDailySnapshotBuilder.TurnoverStats(true, 2000, 3, 4,
                List.of("2026-10-06 111111")));
        assertThat(md).contains("## 실제 거래대금(ACC_TRDVAL, D-133)", "| 거래대금 행(`daily_turnover.csv`) | 2000 |",
                "ACC_TRDVAL이 없거나 빈 행(파일에 넣지 않음) | 3 |", "밖 행(경고) | 4 (0.200%) |", "| 범위 밖 앞 1건 | 2026-10-06 111111 |")
                .doesNotContain("응답에 ACC_TRDVAL이 없습니다");
        String none = CollectorReports.turnoverSection(new KrxDailySnapshotBuilder.TurnoverStats(true, 0, 7, 0, List.of()));
        assertThat(none).contains("**KRX 응답에 ACC_TRDVAL이 없습니다.**");
        assertThat(CollectorReports.turnoverSection(new KrxDailySnapshotBuilder.TurnoverStats(false, 0, 0, 0, List.of())))
                .contains("거래대금 파일 없음(`--without-turnover`");
    }
}
