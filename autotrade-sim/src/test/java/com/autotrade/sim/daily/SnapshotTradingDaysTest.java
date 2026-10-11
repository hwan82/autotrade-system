package com.autotrade.sim.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.sim.daily.DailyBarMarketDataSetTest.MemorySource;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 스냅샷 일봉 날짜로 만든 거래일(D-061). 번들 캘린더(KRX 2026, 3/2 삼일절 대체공휴일 휴장)와 겹치는 구간은 날짜 하나까지 대조한다.
 */
class SnapshotTradingDaysTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final Symbol A = Symbol.krx("005930");
    private static final Symbol B = Symbol.krx("000660");

    private static MemorySource bars(Symbol s, String... dates) {
        MemorySource src = new MemorySource();
        for (String d : dates) {
            src.bar(s, LocalDate.parse(d), "1000", "1000", "1000", "1000", 10, "1");
        }
        return src;
    }

    @Test
    void 캘린더_이전_해는_일봉_날짜를_거래일로_쓰고_캘린더_구간은_대조한다() {
        // 2025-12-31(연말 휴장)·2026-01-01(신정)은 일봉이 없다. 2026-01-02부터는 캘린더와 같아야 한다
        MemorySource src = bars(A, "2025-12-29", "2025-12-30", "2026-01-02", "2026-01-05");
        src.bar(B, LocalDate.parse("2025-12-29"), "1", "1", "1", "1", 1, "1");
        SnapshotTradingDays.Derived d = SnapshotTradingDays.derive(src, "snap", Market.KRX,
                LocalDate.parse("2025-12-29"), LocalDate.parse("2026-01-05"), KRX);

        assertThat(d.days().isTradingDay(LocalDate.parse("2025-12-30"))).isTrue();
        assertThat(d.days().isTradingDay(LocalDate.parse("2025-12-31"))).isFalse();
        assertThat(d.days().previous(LocalDate.parse("2026-01-02"))).isEqualTo(LocalDate.parse("2025-12-30"));
        // 스냅샷 뒤는 캘린더로 잇는다(주간 선정이 그 주 끝까지 묻는다)
        assertThat(d.last()).isEqualTo(KRX.coverageTo());
        assertThat(d.days().isTradingDay(LocalDate.parse("2026-01-06"))).isTrue();
        assertThat(d.digest()).hasSize(64).isEqualTo(SnapshotTradingDays.derive(src, "snap", Market.KRX,
                LocalDate.parse("2025-12-29"), LocalDate.parse("2026-01-05"), KRX).digest());
    }

    @Test
    void 캘린더_거래일에_일봉이_빠지면_멈춘다() {
        MemorySource src = bars(A, "2026-02-26", "2026-03-03"); // 2/27 빠짐
        assertThatThrownBy(() -> SnapshotTradingDays.derive(src, "snap", Market.KRX, LocalDate.parse("2026-02-26"),
                LocalDate.parse("2026-03-03"), KRX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("일봉 없음 [2026-02-27]").hasMessageContaining("일봉 있음 []");
    }

    @Test
    void 캘린더_휴장일에_일봉이_있으면_멈춘다() {
        MemorySource src = bars(A, "2026-02-27", "2026-03-02", "2026-03-03");
        assertThatThrownBy(() -> SnapshotTradingDays.derive(src, "snap", Market.KRX, LocalDate.parse("2026-02-27"),
                LocalDate.parse("2026-03-03"), KRX))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("일봉 있음 [2026-03-02]");
    }

    @Test
    void 주말_일봉은_거부한다() {
        MemorySource src = bars(A, "2025-06-13", "2025-06-14");
        assertThatThrownBy(() -> SnapshotTradingDays.derive(src, "snap", Market.KRX, LocalDate.parse("2025-06-13"),
                LocalDate.parse("2025-06-14"), KRX))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("주말");
    }
}
