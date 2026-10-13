package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.util.List;

/**
 * 고가 근접(중기, logic {@code near-high}): 전일 종가가 직전 L거래일(전일 포함) 최고 종가 × 진입 비율 이상이면(같으면 진입) 진입,
 * 전일 종가가 그 최고 종가 × 청산 비율 미만으로 내려가면(같으면 유지) 로직 청산. 종가가 L개 모자라면 판단하지 않는다.
 */
final class NearHigh extends DailyCloseStrategy {

    private final int lookback;
    private final BigDecimal entryRatio;
    private final BigDecimal exitRatio;

    NearHigh(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.lookback = p.count("signal.lookback-days", 2, 500);
        this.entryRatio = p.decimal("signal.entry-ratio", "0.5", "1.0");
        this.exitRatio = p.decimal("signal.exit-ratio", "0.1", "0.99");
        if (exitRatio.compareTo(entryRatio) >= 0) {
            throw p.fail("signal.exit-ratio", "청산 비율은 진입 비율보다 작아야 합니다");
        }
    }

    @Override
    protected int requiredCloses() {
        return lookback;
    }

    @Override
    protected boolean entryCondition(List<BigDecimal> closes) {
        return closes.size() >= lookback
                && last(closes).compareTo(highest(closes, lookback, 0).multiply(entryRatio)) >= 0;
    }

    @Override
    protected boolean exitCondition(List<BigDecimal> closes) {
        return closes.size() >= lookback
                && last(closes).compareTo(highest(closes, lookback, 0).multiply(exitRatio)) < 0;
    }
}
