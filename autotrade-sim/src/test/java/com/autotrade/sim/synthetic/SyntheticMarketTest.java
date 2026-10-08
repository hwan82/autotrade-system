package com.autotrade.sim.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticMarketTest {

    private static final SyntheticKrxRules RULES = SyntheticKrxRules.INSTANCE;
    private static final SymbolSpec A = new SymbolSpec("900010", new BigDecimal("19950"), 1000, 100, 200);
    private static final SymbolSpec B = new SymbolSpec("900020", new BigDecimal("61000"), 500, 50, 180);
    private static final LocalDate START = LocalDate.of(2026, 3, 6); // 금요일

    @Test
    void 같은_시드면_같은_이벤트_다른_시드면_다른_이벤트() {
        SyntheticMarket gen = new SyntheticMarket(RULES);
        var a = gen.generate(7L, START, 3, List.of(A, B), 350);
        var b = gen.generate(7L, START, 3, List.of(B, A), 350);
        var c = gen.generate(8L, START, 3, List.of(A, B), 350);
        assertThat(b.data().events()).as("종목 입력 순서와도 무관").isEqualTo(a.data().events());
        assertThat(b.data().version()).isEqualTo(a.data().version());
        assertThat(c.data().events()).isNotEqualTo(a.data().events());
        assertThat(c.data().version()).isNotEqualTo(a.data().version());
    }

    @Test
    void 생성_명세가_바뀌면_데이터_스냅샷_ID가_바뀐다() {
        SyntheticMarket gen = new SyntheticMarket(RULES);
        String base = gen.generate(7L, START, 1, List.of(A), 350).data().version();
        SymbolSpec moreVol = new SymbolSpec("900010", new BigDecimal("19950"), 1000, 100, 250);
        assertThat(gen.generate(7L, START, 1, List.of(moreVol), 350).data().version()).isNotEqualTo(base);
        assertThat(gen.generate(7L, START, 1, List.of(A), 400).data().version()).isNotEqualTo(base);
    }

    @Test
    void 종목을_더해도_기존_종목_시세는_그대로다() {
        SyntheticMarket gen = new SyntheticMarket(RULES);
        List<String> alone = prices(gen.generate(7L, START, 2, List.of(A), 350).data().events(), "900010");
        List<String> withB = prices(gen.generate(7L, START, 2, List.of(A, B), 350).data().events(), "900010");
        assertThat(withB).isEqualTo(alone);
    }

    @Test
    void 평일_정규장_안에서만_호가_단위대로_만들고_번호는_오름차순이다() {
        // 50,000원(호가 단위 50 → 100) 경계에서 시작하는 종목으로 갭·하락의 단위 처리도 본다
        SymbolSpec edge = new SymbolSpec("900030", new BigDecimal("50000"), 300, 30, 400);
        var r = new SyntheticMarket(RULES).generate(11L, START, 3, List.of(A, B, edge), 1000);
        assertThat(r.tradingDays()).containsExactly(START, START.plusDays(3), START.plusDays(4));
        assertThat(r.regimes()).hasSize(9).allMatch(x -> x.regime() != DayRegime.Regime.RANGE);
        long prev = 0;
        for (MarketEvent e : r.data().events()) {
            assertThat(e.sequence()).isGreaterThan(prev);
            prev = e.sequence();
            var local = e.exchangeTime().atZone(RULES.zone());
            assertThat(local.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(local.toLocalTime()).isBetween(LocalTime.of(9, 0), LocalTime.of(15, 20));
            assertThat(e.receivedAt()).isAfter(e.exchangeTime());
            if (e instanceof TradeTick t) {
                assertOnTick(t.symbol(), t.price());
            } else if (e instanceof OrderBookSnapshot b) {
                assertThat(b.asks()).hasSize(5);
                assertThat(b.asks().get(0).price()).isGreaterThan(b.bids().get(0).price());
                b.asks().forEach(l -> assertOnTick(b.symbol(), l.price()));
                b.bids().forEach(l -> assertOnTick(b.symbol(), l.price()));
            }
        }
        assertThat(r.data().events()).hasSizeGreaterThan(2 * 3 * 5_000);
    }

    private static void assertOnTick(Symbol s, BigDecimal price) {
        assertThat(RULES.roundToTick(s, price, RoundingMode.DOWN)).isEqualByComparingTo(price);
    }

    private static List<String> prices(List<MarketEvent> events, String code) {
        return events.stream().filter(e -> e.symbol().code().equals(code) && e instanceof TradeTick)
                .map(e -> ((TradeTick) e).price().toPlainString() + "@" + e.exchangeTime()).toList();
    }
}
