package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 이동평균 추세(스윙, logic {@code ma-trend}): 끝난 거래일 종가로 만든 단기 이동평균이 장기 이동평균보다 높으면
 * 그날 첫 체결 이후 진입 신호, 낮아지면 그날 첫 체결 이후 로직 청산. 판단은 전일까지의 종가만 쓰고 체결은 당일 장중이다.
 */
final class MovingAverageTrend extends ManagedStrategy {

    private final int fast;
    private final int slow;
    /**
     * 오늘의 판단(종목별, 하루 첫 체결에 한 번 정함. 판단 불가면 null 값). 청산 주문이 거절·취소돼도 같은 날 다음 이벤트에서
     * 같은 판단으로 다시 청산을 시도하도록 결과를 캐시한다.
     */
    private final Map<Symbol, Boolean> todayTrend = new HashMap<>();

    MovingAverageTrend(TradingRules rules, MarketRules market, ProfileParams p) {
        super(rules, market);
        this.fast = p.count("signal.fast-days", 1, 60);
        this.slow = p.count("signal.slow-days", 2, 250);
        if (fast >= slow) {
            throw p.fail("signal.fast-days", "단기가 장기보다 짧아야 합니다");
        }
    }

    @Override
    protected void onNewDay(SymbolState s) {
        todayTrend.remove(s.symbol());
        entryTried.remove(s.symbol());
    }

    /** 진입은 하루 한 번(첫 판단 때)만 시도한다. */
    private final java.util.Set<Symbol> entryTried = new java.util.HashSet<>();

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        return event instanceof TradeTick && decide(s) == Boolean.TRUE && entryTried.add(s.symbol());
    }

    @Override
    protected boolean logicExit(SymbolState s, MarketEvent event, LocalTime time) {
        return event instanceof TradeTick && decide(s) == Boolean.FALSE;
    }

    private Boolean decide(SymbolState s) {
        if (!todayTrend.containsKey(s.symbol())) {
            todayTrend.put(s.symbol(), trendUp(s));
        }
        return todayTrend.get(s.symbol());
    }

    /** 종가가 모자라면 null(판단 불가). */
    private Boolean trendUp(SymbolState s) {
        List<BigDecimal> closes = s.dailyCloses();
        if (closes.size() < slow) {
            return null;
        }
        return average(closes, fast).compareTo(average(closes, slow)) > 0;
    }

    private static BigDecimal average(List<BigDecimal> closes, int n) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = closes.size() - n; i < closes.size(); i++) {
            sum = sum.add(closes.get(i));
        }
        return sum.divide(BigDecimal.valueOf(n), MathContext.DECIMAL64);
    }
}
