package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 거래량 폭증(logic {@code volume-surge}): 최근 {@code signal.window-minutes}분 거래량이 오늘 앞선 같은 길이 평균의
 * {@code signal.surge-ratio}배 이상이고, 같은 기간 가격이 {@code signal.min-rise-pct}% 이상 오르면 주 신호.
 */
final class VolumeSurge extends ManagedStrategy {

    private final int window;
    private final BigDecimal ratio;
    private final BigDecimal minRise;

    VolumeSurge(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.window = p.count("signal.window-minutes", 1, 60);
        this.ratio = p.decimal("signal.surge-ratio", "1", "50");
        this.minRise = p.percent("signal.min-rise-pct", "0", "10");
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        if (!(event instanceof TradeTick t)) {
            return false;
        }
        BigDecimal r = s.volumeRatio(time, window);
        BigDecimal before = s.priceMinutesAgo(time, window);
        return r != null && before != null && r.compareTo(ratio) >= 0
                && t.price().compareTo(before.multiply(BigDecimal.ONE.add(minRise))) >= 0;
    }
}
