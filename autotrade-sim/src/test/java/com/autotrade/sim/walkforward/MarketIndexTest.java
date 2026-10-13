package com.autotrade.sim.walkforward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.DailyBarSource;
import com.autotrade.marketdata.daily.InstrumentPeriod;
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
 * 벤치마크 지수 손 계산. 종목 A(상장주식수 10), B(1), C(100, 둘째 날이 마지막 일봉인 상장폐지 종목), 날짜 d1~d4.
 *
 * <pre>
 *          d1    d2    d3    d4
 *   A     100   110   121   60.5 (2:1 분할, 수정 계수 1 → 2)
 *   B     200   190   190   209
 *   C      50    40    -     -
 * </pre>
 * d2: 가중치(d1 시총) A 1,000 · B 200 · C 5,000, 수익률 +10% · −5% · −20% → 시총 가중 −910/6,200, 동일가중 −5%.
 * d3: C 제외. A 1,100 · B 190, 수익률 +10% · 0 → 110/1,290, 동일가중 +5%.
 * d4: A 1,210 · B 190, 수익률 0(분할은 수정 계수로 이어짐) · +10% → 19/1,400, 동일가중 +5%.
 */
class MarketIndexTest {

    private static final Symbol A = Symbol.krx("000010");
    private static final Symbol B = Symbol.krx("000020");
    private static final Symbol C = Symbol.krx("000030");
    private static final LocalDate D1 = LocalDate.of(2026, 3, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 3);
    private static final LocalDate D3 = LocalDate.of(2026, 3, 4);
    private static final LocalDate D4 = LocalDate.of(2026, 3, 5);

    private static DailyBar bar(Symbol s, LocalDate d, String close, String adj) {
        BigDecimal c = new BigDecimal(close);
        return new DailyBar(s, d, c, c, c, c, 1000, new BigDecimal(adj));
    }

    private static final class Source implements DailyBarSource {
        private final Map<Symbol, List<DailyBar>> bars = new TreeMap<>();

        Source() {
            bars.put(A, List.of(bar(A, D1, "100", "1"), bar(A, D2, "110", "1"), bar(A, D3, "121", "1"),
                    bar(A, D4, "60.5", "2")));
            bars.put(B, List.of(bar(B, D1, "200", "1"), bar(B, D2, "190", "1"), bar(B, D3, "190", "1"),
                    bar(B, D4, "209", "1")));
            bars.put(C, List.of(bar(C, D1, "50", "1"), bar(C, D2, "40", "1")));
        }

        @Override
        public String snapshotId() {
            return "test";
        }

        @Override
        public List<DailyBar> bars(Symbol symbol, LocalDate from, LocalDate to) {
            List<DailyBar> out = new ArrayList<>();
            for (DailyBar b : bars.getOrDefault(symbol, List.of())) {
                if (!b.date().isBefore(from) && !b.date().isAfter(to)) {
                    out.add(b);
                }
            }
            return out;
        }

        @Override
        public SortedSet<Symbol> universe(Market market, LocalDate date) {
            return new TreeSet<>(bars.keySet());
        }

        @Override
        public Optional<InstrumentPeriod> instrumentOn(Symbol symbol, LocalDate date) {
            return Optional.empty();
        }
    }

    private static final MarketIndex.SharesAsOf SHARES = (s, d) -> {
        if (s.equals(A)) {
            return OptionalLong.of(10);
        }
        return OptionalLong.of(s.equals(B) ? 1 : 100);
    };

    @Test
    void 시총_가중과_동일가중_수익률이_손_계산과_맞는다() {
        MarketIndex idx = MarketIndex.build(new Source(), SHARES, Market.KRX, D1, D4);
        // d2~d4 전체: 시총 가중 L4 = 5290 x 1419 / (6200 x 1290), 동일가중 0.95 x 1.05 x 1.05
        assertThat(idx.capReturn(D2, D4)).isCloseTo(new BigDecimal("0.9385483870967742").subtract(BigDecimal.ONE), within(new BigDecimal("1e-15")));
        assertThat(idx.equalReturn(D2, D4)).isCloseTo(new BigDecimal("0.047375"), within(new BigDecimal("1e-15")));
        // d3~d4: (1400/1290) x (1419/1400) − 1 = 0.1 (분할은 수익률에 영향 없음)
        assertThat(idx.capReturn(D3, D4)).isCloseTo(new BigDecimal("0.1"), within(new BigDecimal("1e-15")));
        // 구간 하나: d2만 = −910/6,200
        assertThat(idx.capReturn(D2, D2)).isCloseTo(new BigDecimal("-0.146774193548387"), within(new BigDecimal("1e-12")));
        assertThat(idx.equalReturn(D2, D2)).isCloseTo(new BigDecimal("-0.05"), within(new BigDecimal("1e-15")));
        // 구성 종목: d2 3개, d3 2개, d4 2개 → 평균 2.3
        assertThat(idx.averageConstituents()).isEqualByComparingTo("2.3");
        assertThat(idx.capExcluded()).isZero();
    }

    @Test
    void 상장폐지_종목은_마지막_일봉일까지만_포함되고_다음_날_빠진다() {
        MarketIndex idx = MarketIndex.build(new Source(), SHARES, Market.KRX, D1, D4);
        // d3 수익률에 C가 있었다면 가중치가 달라진다: C 제외 값(110/1,290)과 같아야 한다
        BigDecimal l3 = idx.capReturn(D3, D3);
        assertThat(l3).isCloseTo(new BigDecimal("0.085271317829457"), within(new BigDecimal("1e-12")));
    }

    @Test
    void 상장주식수를_모르면_시총_가중에서_빼고_센다() {
        MarketIndex idx = MarketIndex.build(new Source(), (s, d) -> s.equals(C) ? OptionalLong.empty() : SHARES.asOf(s, d),
                Market.KRX, D1, D4);
        // C 제외하면 d2 시총 가중 = (100 − 10)/1,200 = 0.075, 동일가중은 그대로
        assertThat(idx.capReturn(D2, D2)).isCloseTo(new BigDecimal("0.075"), within(new BigDecimal("1e-15")));
        assertThat(idx.equalReturn(D2, D2)).isCloseTo(new BigDecimal("-0.05"), within(new BigDecimal("1e-15")));
        assertThat(idx.capExcluded()).isEqualTo(1);
    }

    @Test
    void 기준일_직전_수준이_없으면_거부하고_상장주식수_파일이_없으면_계산하지_않는다() {
        MarketIndex idx = MarketIndex.build(new Source(), SHARES, Market.KRX, D1, D4);
        assertThatThrownBy(() -> idx.capReturn(D1, D4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketIndex.build(new Source(), (com.autotrade.marketdata.daily.SnapshotListedShares) null,
                Market.KRX, D1, D4)).isInstanceOf(IllegalStateException.class).hasMessageContaining("상장주식수");
    }
}
