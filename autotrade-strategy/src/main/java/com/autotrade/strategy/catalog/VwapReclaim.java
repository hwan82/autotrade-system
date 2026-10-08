package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/**
 * VWAP 회복(logic {@code vwap-reclaim}): 체결가가 당일 VWAP보다 {@code signal.dip-pct}% 이상 아래로 내려갔다가
 * 다시 VWAP 위로 올라오면 주 신호. 신호가 나가면 다시 이탈해야 다음 신호가 생긴다.
 */
final class VwapReclaim extends ManagedStrategy {

    private final BigDecimal dip;
    private final Set<Symbol> dipped = new HashSet<>();

    VwapReclaim(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.dip = p.percent("signal.dip-pct", "0.05", "10");
    }

    @Override
    protected void onNewDay(SymbolState s) {
        dipped.remove(s.symbol());
    }

    @Override
    protected void observe(SymbolState s, MarketEvent event, LocalTime time) {
        BigDecimal vwap = s.vwap();
        if (event instanceof TradeTick t && vwap != null
                && t.price().compareTo(vwap.multiply(BigDecimal.ONE.subtract(dip))) <= 0) {
            dipped.add(s.symbol());
        }
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        BigDecimal vwap = s.vwap();
        if (event instanceof TradeTick t && vwap != null && dipped.contains(s.symbol())
                && t.price().compareTo(vwap) > 0) {
            dipped.remove(s.symbol());
            return true;
        }
        return false;
    }
}
