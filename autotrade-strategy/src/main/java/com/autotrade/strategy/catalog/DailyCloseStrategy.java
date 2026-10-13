package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 일봉 종가 판단 로직의 공통 틀(스윙·중기): 판단은 끝난 거래일의 종가({@link SymbolState#dailyCloses})만 쓰고, 하루 첫 체결에
 * 한 번 정해 그날 안에서는 그대로 쓴다(청산 주문이 거절돼도 같은 날 다시 같은 판단으로 청산). 진입 신호는 종목마다 하루 한 번만
 * 낸다. 종가가 모자라면 진입도 청산도 판단하지 않는다(예열 부족). 결과는 종가 목록과 파라미터로만 정해져 결정론적이다.
 */
abstract class DailyCloseStrategy extends ManagedStrategy {

    /** 오늘의 판단(종목별, 하루 첫 체결에 한 번 정함). */
    private record Decision(boolean enter, boolean exit) {
    }

    private final Map<Symbol, Decision> today = new HashMap<>();
    private final Set<Symbol> entryTried = new HashSet<>();

    DailyCloseStrategy(TradingRules rules, MarketRules market) {
        super(rules, market);
    }

    /** 이 종목의 오늘 진입 조건. {@code closes}는 전일까지 종가(오래된 것부터). 종가가 모자라면 false. */
    protected abstract boolean entryCondition(List<BigDecimal> closes);

    /** 진입·청산 판단에 필요한 끝난 거래일 종가 수. 아래 두 조건이 판단을 시작하는 개수와 같아야 한다. */
    protected abstract int requiredCloses();

    @Override
    protected final int requiredHistoryDays() {
        return requiredCloses();
    }

    /** 이 종목의 오늘 로직 청산 조건. 종가가 모자라면 false. */
    protected abstract boolean exitCondition(List<BigDecimal> closes);

    @Override
    protected final void onNewDay(SymbolState s) {
        today.remove(s.symbol());
        entryTried.remove(s.symbol());
    }

    @Override
    protected final boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        return event instanceof TradeTick && decide(s).enter() && entryTried.add(s.symbol());
    }

    @Override
    protected final boolean logicExit(SymbolState s, MarketEvent event, LocalTime time) {
        return event instanceof TradeTick && decide(s).exit();
    }

    private Decision decide(SymbolState s) {
        return today.computeIfAbsent(s.symbol(), k -> {
            List<BigDecimal> closes = s.dailyCloses();
            return new Decision(entryCondition(closes), exitCondition(closes));
        });
    }

    /** 끝에서 {@code skipLast}개를 뺀 마지막 {@code n}개 종가의 최고값. 호출 전에 개수를 확인할 것. */
    protected static BigDecimal highest(List<BigDecimal> closes, int n, int skipLast) {
        int end = closes.size() - skipLast;
        BigDecimal max = closes.get(end - n);
        for (int i = end - n + 1; i < end; i++) {
            if (closes.get(i).compareTo(max) > 0) {
                max = closes.get(i);
            }
        }
        return max;
    }

    protected static BigDecimal average(List<BigDecimal> closes, int n) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = closes.size() - n; i < closes.size(); i++) {
            sum = sum.add(closes.get(i));
        }
        return sum.divide(BigDecimal.valueOf(n), MathContext.DECIMAL64);
    }

    protected static BigDecimal last(List<BigDecimal> closes) {
        return closes.get(closes.size() - 1);
    }
}
