package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.util.List;

/**
 * 단기 반전(스윙, logic {@code short-reversal}): 전일 종가가 직전 M거래일(전일 포함) 종가 평균보다 높고(추세 필터, 같으면 아님)
 * 직전 K거래일 연속으로 종가가 내렸으면 진입, 전일 종가가 직전 X거래일 평균을 넘으면(같으면 아님) 로직 청산. 진입과 청산 조건이
 * 같은 날 함께 참이면 사자마자 파는 왕복을 막기 위해 진입하지 않는다.
 */
final class ShortReversal extends DailyCloseStrategy {

    private final int trendDays;
    private final int downDays;
    private final int exitDays;

    ShortReversal(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.trendDays = p.count("signal.trend-days", 5, 250);
        this.downDays = p.count("signal.down-days", 1, 10);
        this.exitDays = p.count("signal.exit-days", 2, 60);
    }

    /** 진입은 max(M, K+1)개, 청산은 X개부터 판단한다. 둘 다 판단할 수 있는 개수. */
    @Override
    protected int requiredCloses() {
        return Math.max(Math.max(trendDays, downDays + 1), exitDays);
    }

    @Override
    protected boolean entryCondition(List<BigDecimal> closes) {
        if (closes.size() < Math.max(trendDays, downDays + 1) || exitCondition(closes)) {
            return false;
        }
        if (last(closes).compareTo(average(closes, trendDays)) <= 0) {
            return false;
        }
        for (int i = closes.size() - downDays; i < closes.size(); i++) {
            if (closes.get(i).compareTo(closes.get(i - 1)) >= 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean exitCondition(List<BigDecimal> closes) {
        return closes.size() >= exitDays && last(closes).compareTo(average(closes, exitDays)) > 0;
    }
}
