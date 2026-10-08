package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.profile.ProfileParams;
import java.time.LocalTime;
import java.util.SplittableRandom;

/**
 * 무작위 진입 대조군(logic {@code random}): 체결 이벤트마다 {@code signal.per-mille}/1000 확률로 주 신호.
 * 청산·비중·한도는 다른 프로필과 같은 코드라, 이 프로필의 성과 분포가 "진입 로직에 정보가 없을 때"의 기준선이다.
 * 난수는 Run 시드로만 만든다(결정론).
 */
final class RandomEntry extends ManagedStrategy {

    private final int perMille;
    private final SplittableRandom random;

    RandomEntry(TradingRules rules, MarketRules market, ProfileParams p, long runSeed) {
        super(rules, market);
        this.perMille = p.count("signal.per-mille", 1, 1000);
        this.random = new SplittableRandom(runSeed);
    }

    @Override
    protected boolean trigger(SymbolState s, MarketEvent event, LocalTime time) {
        return event instanceof TradeTick && random.nextInt(1000) < perMille;
    }
}
