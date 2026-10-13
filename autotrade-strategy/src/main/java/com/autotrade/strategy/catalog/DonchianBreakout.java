package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.util.List;

/**
 * 채널 돌파(스윙·중기, logic {@code breakout-days}): 전일 종가가 그 전 N거래일 최고 종가를 넘으면(같으면 아님) 그날 첫 체결 이후
 * 진입. 로직 청산은 없고 공통 청산(손절, 추적 손절, 최대 보유일)만 쓴다. 종가가 N+1개 모자라면 판단하지 않는다.
 */
final class DonchianBreakout extends DailyCloseStrategy {

    private final int lookback;

    DonchianBreakout(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.lookback = p.count("signal.lookback-days", 2, 250);
    }

    @Override
    protected int requiredCloses() {
        return lookback + 1;
    }

    @Override
    protected boolean entryCondition(List<BigDecimal> closes) {
        if (closes.size() < requiredCloses()) {
            return false;
        }
        return last(closes).compareTo(highest(closes, lookback, 1)) > 0;
    }

    @Override
    protected boolean exitCondition(List<BigDecimal> closes) {
        return false;
    }
}
