package com.autotrade.sim.metrics;

import com.autotrade.domain.Money;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.exchange.Fill;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** {@link RunMetrics} 계산. 순수 함수이며 입력이 같으면 결과가 비트 단위로 같다. */
public final class MetricsCalculator {

    /** 연환산 거래일 수 `[가정]`(KRX·NYSE 모두 연 250~252일). */
    static final int TRADING_DAYS = 252;
    static final int SCALE = 6;
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private MetricsCalculator() {
    }

    /**
     * @param from 실험 시작일(CAGR 기간)
     * @param to 실험 종료일
     * @param equity 거래일 마감 평가금액, 날짜 순
     * @param benchmarkReturn 벤치마크 수익률. 없으면 null
     */
    public static RunMetrics compute(
            Money seed, LocalDate from, LocalDate to, List<EquityPoint> equity, List<AppliedFill> fills,
            List<RoundTrip> roundTrips, BigDecimal benchmarkReturn) {
        Objects.requireNonNull(seed, "seed");
        Money finalEquity = equity.isEmpty() ? seed : equity.get(equity.size() - 1).equity();
        BigDecimal growth = finalEquity.amount().divide(seed.amount(), DecimalMath.MC);
        BigDecimal totalReturn = growth.subtract(BigDecimal.ONE);

        BigDecimal years = BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1).divide(DAYS_PER_YEAR, DecimalMath.MC);
        BigDecimal cagr = growth.signum() <= 0
                ? BigDecimal.ONE.negate()
                : DecimalMath.exp(DecimalMath.ln(growth).divide(years, DecimalMath.MC)).subtract(BigDecimal.ONE);

        BigDecimal peak = seed.amount();
        BigDecimal mdd = BigDecimal.ZERO;
        BigDecimal prev = seed.amount();
        BigDecimal[] returns = new BigDecimal[equity.size()];
        BigDecimal equitySum = BigDecimal.ZERO;
        for (int i = 0; i < equity.size(); i++) {
            BigDecimal e = equity.get(i).equity().amount();
            equitySum = equitySum.add(e);
            returns[i] = e.divide(prev, DecimalMath.MC).subtract(BigDecimal.ONE);
            prev = e;
            if (e.compareTo(peak) > 0) {
                peak = e;
            }
            BigDecimal dd = peak.subtract(e).divide(peak, DecimalMath.MC);
            if (dd.compareTo(mdd) > 0) {
                mdd = dd;
            }
        }

        BigDecimal sharpe = sharpe(returns);

        int wins = 0;
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        long holdingDays = 0;
        for (RoundTrip rt : roundTrips) {
            BigDecimal pnl = rt.pnl().amount();
            if (pnl.signum() > 0) {
                wins++;
                gains = gains.add(pnl);
            } else if (pnl.signum() < 0) {
                losses = losses.add(pnl.negate());
            }
            holdingDays += ChronoUnit.DAYS.between(rt.entryDate(), rt.exitDate());
        }
        int n = roundTrips.size();
        BigDecimal winRate = n == 0 ? null : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(n), DecimalMath.MC);
        BigDecimal profitFactor = losses.signum() == 0 ? null : gains.divide(losses, DecimalMath.MC);
        BigDecimal avgHolding = n == 0 ? null : BigDecimal.valueOf(holdingDays).divide(BigDecimal.valueOf(n), DecimalMath.MC);

        Money costs = Money.zero(seed.currency());
        BigDecimal traded = BigDecimal.ZERO;
        for (AppliedFill a : fills) {
            Fill f = a.fill();
            costs = costs.plus(f.fees().total());
            traded = traded.add(f.notional().amount());
        }
        BigDecimal turnover = equity.isEmpty() || equitySum.signum() == 0 ? BigDecimal.ZERO
                : traded.divide(BigDecimal.valueOf(2), DecimalMath.MC)
                        .divide(equitySum.divide(BigDecimal.valueOf(equity.size()), DecimalMath.MC), DecimalMath.MC);
        BigDecimal gross = finalEquity.minus(seed).plus(costs).amount();
        BigDecimal costShare = gross.signum() <= 0 ? null : costs.amount().divide(gross, DecimalMath.MC);

        BigDecimal excess = benchmarkReturn == null ? null : totalReturn.subtract(benchmarkReturn);
        return new RunMetrics(
                finalEquity, s(totalReturn), s(cagr), s(mdd), s(sharpe), s(winRate), s(profitFactor), s(avgHolding),
                fills.size(), n, s(turnover), costs, s(costShare), s(benchmarkReturn), s(excess));
    }

    private static BigDecimal sharpe(BigDecimal[] r) {
        if (r.length < 2) {
            return null;
        }
        BigDecimal n = BigDecimal.valueOf(r.length);
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal x : r) {
            sum = sum.add(x, DecimalMath.MC);
        }
        BigDecimal mean = sum.divide(n, DecimalMath.MC);
        BigDecimal sq = BigDecimal.ZERO;
        for (BigDecimal x : r) {
            BigDecimal d = x.subtract(mean, DecimalMath.MC);
            sq = sq.add(d.multiply(d, DecimalMath.MC), DecimalMath.MC);
        }
        BigDecimal variance = sq.divide(BigDecimal.valueOf(r.length - 1L), DecimalMath.MC);
        if (variance.signum() == 0) {
            return null;
        }
        return mean.divide(variance.sqrt(DecimalMath.MC), DecimalMath.MC)
                .multiply(BigDecimal.valueOf(TRADING_DAYS).sqrt(DecimalMath.MC), DecimalMath.MC);
    }

    private static BigDecimal s(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}
