package com.autotrade.sim.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.financial.FinancialFiling;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.calc.SelectionData;
import com.autotrade.selection.calc.SnapshotSelectionData;
import com.autotrade.selection.calc.TradingDays;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 실험실 선정 공급자: 선정 계산 결과가 날짜별 선정 표(STR-17 공유)로 들어가는지. KRX 2026 캘린더(9/24·25 추석 휴장)로
 * 주간 갱신 결과 하나가 한 주를 덮는지, 매일 갱신은 거래일마다 계산되는지, 결과가 직전 거래일까지의 데이터로만 정해지는지 본다.
 */
class CalculatedSelectionSourceTest {

    private static final ExchangeCalendar KRX = ExchangeCalendar.bundled(Market.KRX);
    private static final Symbol UP = Symbol.krx("000010");
    private static final Symbol DOWN = Symbol.krx("000020");

    /** UP은 매일 1%씩 오르고, DOWN은 9/30까지 오르다가 10/1부터 내린다. */
    static final class Data implements SelectionData {
        final TradingDays days = TradingDays.of(KRX);
        int calculations;

        @Override
        public Market market() {
            return Market.KRX;
        }

        @Override
        public TradingDays tradingDays() {
            return days;
        }

        @Override
        public SortedSet<Symbol> universe(LocalDate date) {
            calculations++; // 계산 한 번에 한 번 부른다
            return new TreeSet<>(List.of(UP, DOWN));
        }

        @Override
        public Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date) {
            return Optional.of(new InstrumentPeriod(symbol, "x", InstrumentKind.STOCK, "KOSPI", LocalDate.of(2026, 1, 1),
                    Optional.empty()));
        }

        @Override
        public List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to) {
            List<DailyBar> out = new ArrayList<>();
            int i = 0;
            for (LocalDate d : KRX.tradingDays(from, to)) {
                boolean rising = symbol.equals(UP) || d.isBefore(LocalDate.of(2026, 10, 1));
                BigDecimal close = BigDecimal.valueOf(rising ? 10000 + 100L * i : 9000 - 100L * i);
                out.add(new DailyBar(symbol, d, close, close, close, close, 1000, BigDecimal.ONE));
                i++;
            }
            return out;
        }

        @Override
        public OptionalLong listedShares(Symbol symbol, LocalDate date) {
            return OptionalLong.empty();
        }

        @Override
        public List<FinancialFiling> filings(Symbol symbol) {
            return List.of();
        }

        @Override
        public LocalDate barsLastTradingDate() {
            return LocalDate.of(2026, 10, 30);
        }

        @Override
        public Optional<LocalDate> sharesAsOf() {
            return Optional.empty();
        }

        @Override
        public String barsSnapshotId() {
            return "synthetic";
        }

        @Override
        public String barsSha256() {
            return "a".repeat(64);
        }

        @Override
        public Optional<String> sharesSnapshotId() {
            return Optional.empty();
        }

        @Override
        public Optional<FinancialSnapshotInfo> financials() {
            return Optional.empty();
        }

        @Override
        public String calendarDigest() {
            return SnapshotSelectionData.calendarDigest(KRX);
        }
    }

    @Test
    void 주간_결과_하나가_휴장_낀_주를_덮고_계좌들이_공유한다() {
        SelectionProfile weekly = rising("POST_CLOSE_WEEKLY", TradingStyle.POSITION);
        Data data = new Data();
        CalculatedSelectionSource source = new CalculatedSelectionSource(new SelectionCalculator(data));
        List<LocalDate> dates = KRX.tradingDays(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 9));
        SelectionSchedule schedule = SelectionSchedule.compute(
                List.of(Combination.of(weekly, strategy(TradingStyle.POSITION)),
                        Combination.of(weekly, strategy2(TradingStyle.POSITION))), dates, source);

        // 9/21 주(9/24·25 휴장), 9/28 주, 10/5 주(10/5·9 휴장 → 첫 거래일 10/6) 각각 결과 하나
        assertThat(source.results(weekly.selectionHash()).keySet()).containsExactly(
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 6));
        assertThat(data.calculations).as("주간 결과는 주마다 한 번만 계산(두 조합이 공유)").isEqualTo(3);
        assertThat(source.results(weekly.selectionHash()).get(LocalDate.of(2026, 9, 21)).applyTo())
                .isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(schedule.days(weekly.selectionHash()).values()).allSatisfy(d -> assertThat(d.isValid()).isTrue());
        // 10/2(금)는 9/28 주 결과(9/25까지 데이터 → 9/25는 휴장이라 9/23까지): DOWN도 아직 오르는 중
        assertThat(schedule.day(weekly.selectionHash(), LocalDate.of(2026, 10, 2)).symbols()).containsExactly(UP, DOWN);
        // 10/6 주 결과는 10/2까지 데이터: DOWN은 10/1부터 하락 → 탈락
        assertThat(schedule.day(weekly.selectionHash(), LocalDate.of(2026, 10, 7)).symbols()).containsExactly(UP);
    }

    @Test
    void 매일_갱신은_거래일마다_계산하고_같은_입력이면_같은_결과_해시다() {
        SelectionProfile daily = rising("POST_CLOSE_DAILY", TradingStyle.SWING);
        List<LocalDate> dates = KRX.tradingDays(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 6));
        CalculatedSelectionSource a = new CalculatedSelectionSource(new SelectionCalculator(new Data()));
        CalculatedSelectionSource b = new CalculatedSelectionSource(new SelectionCalculator(new Data()));
        SelectionSchedule sa = SelectionSchedule.compute(List.of(Combination.of(daily, strategy(TradingStyle.SWING))), dates, a);
        SelectionSchedule sb = SelectionSchedule.compute(List.of(Combination.of(daily, strategy(TradingStyle.SWING))), dates, b);
        assertThat(a.results(daily.selectionHash())).hasSize(dates.size());
        assertThat(sa.days(daily.selectionHash())).isEqualTo(sb.days(daily.selectionHash()));
        // 10/1은 9/30까지 데이터라 DOWN 포함, 10/2는 10/1 하락을 봄
        assertThat(sa.day(daily.selectionHash(), LocalDate.of(2026, 10, 1)).symbols()).containsExactly(UP, DOWN);
        assertThat(sa.day(daily.selectionHash(), LocalDate.of(2026, 10, 2)).symbols()).containsExactly(UP);
    }

    /** 직전 1거래일 수익률 > 0. */
    private static SelectionProfile rising(String refresh, TradingStyle style) {
        return new SelectionProfile("rising-" + style.name().toLowerCase(), 1, Market.KRX, style, new TreeMap<>(Map.of(
                "hypothesis", "직전 거래일에 오른 종목", "refresh", refresh, "max-symbols", "10",
                "gate.01.metric", "return", "gate.01.window", "1", "gate.01.op", "GT", "gate.01.value", "0")));
    }

    private static StrategyProfile strategy(TradingStyle style) {
        return new StrategyProfile("s", 1, Market.KRX, style, new TreeMap<>(Map.of("hypothesis", "h", "logic", "ma-trend")));
    }

    private static StrategyProfile strategy2(TradingStyle style) {
        return new StrategyProfile("s2", 1, Market.KRX, style, new TreeMap<>(Map.of("hypothesis", "h", "logic", "random-daily")));
    }
}
