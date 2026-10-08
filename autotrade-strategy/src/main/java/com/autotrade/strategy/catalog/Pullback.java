package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 눌림목(logic {@code pullback}): 당일 고가가 시가 대비 {@code signal.trend-pct}% 이상 오른 상승일에, 고가 대비
 * {@code signal.pullback-pct}% 이상 밀린 뒤 그 눌림 저점에서 {@code signal.bounce-pct}% 반등하면 주 신호.
 */
final class Pullback extends ManagedStrategy {

    private final BigDecimal trend;
    private final BigDecimal pullback;
    private final BigDecimal bounce;
    /** 눌림이 진행 중인 종목의 눌림 저점. */
    private final Map<Symbol, BigDecimal> pullbackLow = new HashMap<>();

    Pullback(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.trend = p.percent("signal.trend-pct", "0.1", "20");
        this.pullback = p.percent("signal.pullback-pct", "0.1", "20");
        this.bounce = p.percent("signal.bounce-pct", "0.05", "10");
    }

    @Override
    protected void onNewDay(SymbolState s) {
        pullbackLow.remove(s.symbol());
    }

    @Override
    protected void observe(SymbolState s, MarketEvent event, LocalTime time) {
        if (!(event instanceof TradeTick t) || s.dayOpen() == null) {
            return;
        }
        boolean upDay = s.dayHigh().compareTo(s.dayOpen().multiply(BigDecimal.ONE.add(trend))) >= 0;
        BigDecimal low = pullbackLow.get(s.symbol());
        if (low != null) {
            pullbackLow.put(s.symbol(), low.min(t.price()));
        } else if (upDay && t.price().compareTo(s.dayHigh().multiply(BigDecimal.ONE.subtract(pullback))) <= 0) {
            pullbackLow.put(s.symbol(), t.price());
        }
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        BigDecimal low = pullbackLow.get(s.symbol());
        if (event instanceof TradeTick t && low != null
                && t.price().compareTo(low.multiply(BigDecimal.ONE.add(bounce))) >= 0) {
            pullbackLow.remove(s.symbol());
            return true;
        }
        return false;
    }
}
