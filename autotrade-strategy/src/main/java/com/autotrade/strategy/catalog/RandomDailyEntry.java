package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.profile.ProfileParams;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 일봉 무작위 진입 대조군(logic {@code random-daily}, 스윙·중기, STR-17): 종목마다 거래일 첫 체결에서 한 번
 * {@code signal.per-mille}/1000 확률로 그날 진입 신호를 정한다. 판단 단위가 하루라 스윙·중기 지표 로직(전일 종가로 하루
 * 한 번 판단, 다음 체결부터 체결)과 같은 시점에 진입한다. 청산·비중·한도·최대 보유일은 다른 프로필과 같은 코드라, 같은
 * 선정과 묶으면 "선정은 같고 진입에 정보가 없을 때"의 기준선이 된다.
 *
 * <p>난수는 Run 시드로만 만들고, 보유·한도 상태와 무관하게 (거래일, 종목) 첫 체결마다 정확히 한 번 뽑는다. 그래서 난수열이
 * 앞선 체결 결과에 따라 달라지지 않는다(결정론, 대조군끼리 독립).
 */
final class RandomDailyEntry extends ManagedStrategy {

    private final int perMille;
    private final SplittableRandom random;
    /** 오늘 진입 신호가 켜진 종목. 진입을 시도하면 지운다(하루 한 번). */
    private final Set<Symbol> armedToday = new HashSet<>();
    /** 오늘 이미 뽑은 종목. */
    private final Set<Symbol> drawnToday = new HashSet<>();

    RandomDailyEntry(TradingRules rules, MarketRules market, ProfileParams p, long runSeed) {
        super(rules, market);
        this.perMille = p.count("signal.per-mille", 1, 1000);
        this.random = new SplittableRandom(runSeed);
    }

    @Override
    protected void onNewDay(SymbolState s) {
        armedToday.remove(s.symbol());
        drawnToday.remove(s.symbol());
    }

    @Override
    protected void observe(SymbolState s, MarketEvent event, LocalTime time) {
        if (event instanceof TradeTick && drawnToday.add(s.symbol()) && random.nextInt(1000) < perMille) {
            armedToday.add(s.symbol());
        }
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        return event instanceof TradeTick && armedToday.remove(s.symbol());
    }
}
