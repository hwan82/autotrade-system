package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 시가 범위 돌파(ORB, logic {@code orb}): 첫 체결부터 {@code signal.range-minutes}분 동안의 고가를 범위 고가로 두고,
 * 범위가 끝난 뒤 체결가가 범위 고가를 처음 넘으면 주 신호. 종목당 하루 한 번.
 */
final class OpeningRangeBreakout extends ManagedStrategy {

    private final int rangeMinutes;
    private final Map<Symbol, BigDecimal> rangeHigh = new HashMap<>();
    private final Set<Symbol> rangeClosed = new HashSet<>();
    private final Set<Symbol> firedToday = new HashSet<>();

    OpeningRangeBreakout(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.rangeMinutes = p.count("signal.range-minutes", 5, 120);
    }

    @Override
    protected void onNewDay(SymbolState s) {
        rangeHigh.remove(s.symbol());
        rangeClosed.remove(s.symbol());
        firedToday.remove(s.symbol());
    }

    @Override
    protected void observe(SymbolState s, MarketEvent event, LocalTime time) {
        if (!(event instanceof TradeTick t) || rangeClosed.contains(s.symbol())) {
            return;
        }
        // 지금 체결이 범위 밖이면 범위를 닫는다. 범위를 닫은 체결은 범위 고가에 넣지 않는다(돌파 판단 대상)
        if (s.minutesSinceFirstTrade(time) > rangeMinutes) {
            rangeClosed.add(s.symbol());
            return;
        }
        rangeHigh.merge(s.symbol(), t.price(), BigDecimal::max);
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        Symbol sym = s.symbol();
        if (!(event instanceof TradeTick t) || !rangeClosed.contains(sym) || firedToday.contains(sym)) {
            return false;
        }
        BigDecimal high = rangeHigh.get(sym);
        if (high != null && t.price().compareTo(high) > 0) {
            firedToday.add(sym);
            return true;
        }
        return false;
    }
}
