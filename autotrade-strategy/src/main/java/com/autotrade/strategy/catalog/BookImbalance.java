package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 호가 불균형(logic {@code book-imbalance}): 상위 5단계 매수 잔량 합이 매도 잔량 합의 {@code signal.ratio}배 이상인
 * 호가 스냅샷이 {@code signal.snapshots}번 연속이면 주 신호. 신호가 나가면 연속 횟수를 다시 센다.
 */
final class BookImbalance extends ManagedStrategy {

    private final BigDecimal ratio;
    private final int needed;
    private final Map<Symbol, Integer> streak = new HashMap<>();

    BookImbalance(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.ratio = p.decimal("signal.ratio", "1", "20");
        this.needed = p.count("signal.snapshots", 1, 100);
    }

    @Override
    protected void onNewDay(SymbolState s) {
        streak.remove(s.symbol());
    }

    @Override
    protected void observe(SymbolState s, MarketEvent event, LocalTime time) {
        if (event instanceof OrderBookSnapshot) {
            boolean heavyBid = s.askDepth() > 0
                    && BigDecimal.valueOf(s.bidDepth()).compareTo(BigDecimal.valueOf(s.askDepth()).multiply(ratio)) >= 0;
            streak.put(s.symbol(), heavyBid ? streak.getOrDefault(s.symbol(), 0) + 1 : 0);
        }
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        if (streak.getOrDefault(s.symbol(), 0) >= needed) {
            streak.put(s.symbol(), 0);
            return true;
        }
        return false;
    }
}
