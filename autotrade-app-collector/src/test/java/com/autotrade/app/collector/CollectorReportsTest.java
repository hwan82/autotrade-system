package com.autotrade.app.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectorReportsTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);

    private static KrxDailySnapshotBuilder.Stats stats(List<LocalDate> empty, List<KrxDailySnapshotBuilder.BoardGap> gaps,
            List<KrxDailySnapshotBuilder.AdjustmentDay> mass) {
        return new KrxDailySnapshotBuilder.Stats(1, 1, 0, 0, 0, 0, 0, 0, LocalDate.of(2024, 1, 2), LocalDate.of(2026, 10, 8),
                empty, gaps, mass);
    }

    @Test
    void 빈_평일을_연도별로_세고_캘린더로_나눈다() {
        var s = stats(List.of(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 4, 10), LocalDate.of(2026, 9, 23),
                LocalDate.of(2026, 9, 24)), List.of(), List.of());
        String md = CollectorReports.gapSections(s, KRX);
        assertThat(md).contains("| 2024 | 2 | 0 | 0 | 2 | 03-01, 04-10 |")
                .contains("| 2026 | 2 | 1 | 1 | 0 | 09-23(거래일!), 09-24 |")
                .contains("## 시장 하나만 빈 날\n\n").contains("## 같은 날 여러 종목 수정 이벤트(경고)");
        var w = CollectorReports.warnings(s, KRX);
        assertThat(w.emptyTradingDays()).isEqualTo(1);
        assertThat(w.any()).isTrue();
    }

    @Test
    void 시장_공백과_수정_이벤트_몰린_날을_직전_공백과_함께_적는다() {
        LocalDate mon = LocalDate.of(2024, 3, 4);
        var s = stats(List.of(LocalDate.of(2024, 3, 1)), List.of(new KrxDailySnapshotBuilder.BoardGap(mon, KrxBoard.KOSDAQ)),
                List.of(new KrxDailySnapshotBuilder.AdjustmentDay(LocalDate.of(2024, 3, 5), 1700, 2600, mon),
                        new KrxDailySnapshotBuilder.AdjustmentDay(LocalDate.of(2024, 3, 4), 35, 900, LocalDate.of(2024, 3, 1))));
        String md = CollectorReports.gapSections(s, KRX);
        assertThat(md).contains("| 2024-03-04 | KOSDAQ |")
                .contains("| 2024-03-05 | 1700 | 2600 | 65.4% | 2024-03-04 (KOSDAQ 공백) |")
                .contains("| 2024-03-04 | 35 | 900 | 3.9% | 2024-03-01 (빈 평일, 캘린더 범위 밖(대조 필요)) |");
        assertThat(CollectorReports.warnings(s, KRX)).isEqualTo(new CollectorReports.DailyWarnings(0, 1, 2));
    }

    @Test
    void 공백이_없으면_없음으로_적고_경고도_없다() {
        var s = stats(List.of(), List.of(), List.of());
        assertThat(CollectorReports.gapSections(s, KRX)).contains("| - | 0 | 0 | 0 | 0 | |").contains("없음\n");
        assertThat(CollectorReports.warnings(s, KRX).any()).isFalse();
    }
}
