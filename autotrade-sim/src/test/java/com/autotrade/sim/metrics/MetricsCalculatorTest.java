package com.autotrade.sim.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import com.autotrade.sim.account.RoundTrip;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SIM-11 지표 골든: 손 계산 기대값. */
class MetricsCalculatorTest {

    private static final Money SEED = krw(1000);
    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);

    @Test
    void 수익률_MDD_CAGR_샤프() {
        // 1100, 990, 1210을 20번 되풀이한 60거래일(샤프 20일, CAGR 60일 최소 관측 일수를 채움)
        List<EquityPoint> eq = new ArrayList<>();
        long[] cycle = {1100, 990, 1210};
        for (int i = 0; i < 60; i++) {
            eq.add(point(D1.plusDays(i), cycle[i % 3]));
        }
        // 기간 365일이면 CAGR = 총수익률
        RunMetrics m = MetricsCalculator.compute(SEED, D1, D1.plusDays(364), eq, List.of(), List.of(), null);
        assertThat(m.totalReturn()).isEqualByComparingTo("0.21");
        assertThat(m.cagr()).isEqualByComparingTo("0.21");
        assertThat(m.maxDrawdown()).isEqualByComparingTo("0.181818"); // (1210 − 990) / 1210
        // 일별 수익률 0.1, 그 뒤 −0.1, 0.2222…, −0.0909…를 되풀이: 평균 / 표본 표준편차 × √252(Python decimal로 계산)
        assertThat(m.sharpe()).isEqualByComparingTo("1.432721");
        assertThat(m.finalEquity()).isEqualTo(krw(1210));
        assertThat(m.turnover()).isEqualByComparingTo("0");

        // 2년(730일)이면 √1.21 − 1 = 0.1
        RunMetrics two = MetricsCalculator.compute(SEED, D1, D1.plusDays(729), eq, List.of(), List.of(), null);
        assertThat(two.cagr()).isEqualByComparingTo("0.1");
    }

    @Test
    void 관측_일수가_짧으면_샤프와_CAGR은_판정_불가다() {
        List<EquityPoint> eq = new ArrayList<>();
        long[] cycle = {1100, 990, 1210};
        for (int i = 0; i < MetricsCalculator.MIN_CAGR_DAYS - 1; i++) {
            eq.add(point(D1.plusDays(i), cycle[i % 3]));
        }
        RunMetrics m59 = MetricsCalculator.compute(SEED, D1, D1.plusDays(364), eq, List.of(), List.of(), null);
        assertThat(m59.cagr()).isNull();
        assertThat(m59.sharpe()).isNotNull();
        assertThat(m59.totalReturn()).isEqualByComparingTo("-0.01"); // 59번째 점은 990

        List<EquityPoint> short19 = eq.subList(0, MetricsCalculator.MIN_SHARPE_DAYS - 1);
        RunMetrics m19 = MetricsCalculator.compute(SEED, D1, D1.plusDays(18), short19, List.of(), List.of(), null);
        assertThat(m19.sharpe()).isNull();
        assertThat(m19.cagr()).isNull();
        assertThat(m19.maxDrawdown()).isEqualByComparingTo("0.181818");
        assertThat(MetricsCalculator.compute(SEED, D1, D1.plusDays(19), eq.subList(0, 20), List.of(), List.of(), null)
                .sharpe()).isNotNull();
    }

    @Test
    void 승률_손익비_보유일_비용비중_초과수익() {
        Symbol s = Symbol.krx("005930");
        List<RoundTrip> trips = List.of(
                new RoundTrip("L1", s, D1, D1.plusDays(2), krw(300)),
                new RoundTrip("L2", s, D1, D1, krw(-100)),
                new RoundTrip("L3", s, D1, D1.plusDays(4), krw(-50)),
                new RoundTrip("L4", s, D1, D1.plusDays(3), krw(50)));
        List<EquityPoint> eq = List.of(point(D1, 1200));
        RunMetrics m = MetricsCalculator.compute(SEED, D1, D1, eq, List.of(), trips, new BigDecimal("0.05"));
        assertThat(m.winRate()).isEqualByComparingTo("0.5");
        assertThat(m.profitFactor()).isEqualByComparingTo("2.333333"); // 350 / 150
        assertThat(m.avgHoldingDays()).isEqualByComparingTo("2.25"); // (2 + 0 + 4 + 3) / 4
        assertThat(m.roundTripCount()).isEqualTo(4);
        assertThat(m.excessReturn()).isEqualByComparingTo("0.15");
        assertThat(m.sharpe()).isNull(); // 거래일 1일
        assertThat(m.costShare()).isEqualByComparingTo("0"); // 비용 0
    }

    @Test
    void 정의할_수_없는_지표는_null() {
        RunMetrics m = MetricsCalculator.compute(SEED, D1, D1, List.of(), List.of(), List.of(), null);
        assertThat(m.totalReturn()).isEqualByComparingTo("0");
        assertThat(m.winRate()).isNull();
        assertThat(m.profitFactor()).isNull();
        assertThat(m.avgHoldingDays()).isNull();
        assertThat(m.costShare()).isNull();
        assertThat(m.benchmarkReturn()).isNull();
        assertThat(m.excessReturn()).isNull();
    }

    @Test
    void 로그와_지수는_서로_역함수이고_double을_쓰지_않는다() {
        BigDecimal x = new BigDecimal("1.21");
        assertThat(DecimalMath.exp(DecimalMath.ln(x)).setScale(30, java.math.RoundingMode.HALF_EVEN))
                .isEqualByComparingTo("1.21");
        assertThat(DecimalMath.ln(BigDecimal.ONE)).isEqualByComparingTo("0");
        assertThat(DecimalMath.ln(new BigDecimal("1000000")).setScale(12, java.math.RoundingMode.HALF_EVEN))
                .isEqualByComparingTo("13.815510557964"); // ln(10^6)
        assertThat(DecimalMath.exp(BigDecimal.ONE).setScale(12, java.math.RoundingMode.HALF_EVEN))
                .isEqualByComparingTo("2.718281828459");
    }

    private static EquityPoint point(LocalDate d, long equity) {
        return new EquityPoint(d, Instant.EPOCH, krw(equity), krw(equity));
    }

    private static Money krw(long v) {
        return Money.of(v, Money.KRW);
    }
}
