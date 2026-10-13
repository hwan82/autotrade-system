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
 * 전일 고점 돌파(logic {@code prior-day-high}): 시가가 전일 고가 이하로 시작한 날, 체결가가 전일 고가를
 * {@code signal.margin-pct}% 넘어서면 주 신호. 갭으로 이미 넘어선 날은 돌파로 보지 않는다. 종목당 하루 한 번.
 * 실험 첫날은 전일 값이 없어 신호가 없다.
 */
final class PriorDayHighBreakout extends ManagedStrategy {

    private final BigDecimal margin;
    private final Set<Symbol> firedToday = new HashSet<>();

    PriorDayHighBreakout(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.margin = p.percent("signal.margin-pct", "0", "5");
    }

    @Override
    protected int requiredHistoryDays() {
        return 1;
    }

    @Override
    protected void onNewDay(SymbolState s) {
        firedToday.remove(s.symbol());
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        BigDecimal pdh = s.prevDayHigh();
        if (!(event instanceof TradeTick t) || pdh == null || s.dayOpen() == null
                || s.dayOpen().compareTo(pdh) > 0 || firedToday.contains(s.symbol())) {
            return false;
        }
        if (t.price().compareTo(pdh.multiply(BigDecimal.ONE.add(margin))) > 0) {
            firedToday.add(s.symbol());
            return true;
        }
        return false;
    }
}
