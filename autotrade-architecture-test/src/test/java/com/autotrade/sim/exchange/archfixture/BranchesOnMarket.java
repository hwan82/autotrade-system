package com.autotrade.sim.exchange.archfixture;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.time.ZoneId;

/**
 * ArchitectureTest가 엔진 패키지의 시장 분기(OVS-02)를 잡아내는지 확인하는 일부러 잘못된 코드. 운영 코드에서 참조하지 않는다.
 * enum switch는 컴파일러가 만든 같은 패키지의 보조 클래스({@code BranchesOnMarket$1})가 상수를 읽으므로 패키지 단위로 들여와 본다.
 */
public final class BranchesOnMarket {

    static ZoneId zoneBySwitch(Market market) {
        switch (market) {
            case KRX:
                return ZoneId.of("Asia/Seoul");
            default:
                return ZoneId.of("America/New_York");
        }
    }

    static boolean isKrx(Symbol symbol) {
        return symbol.market() == Market.KRX;
    }

    static Symbol samsung() {
        return Symbol.krx("005930");
    }

    /** 허용: 시장 차이를 MarketRules에 묻는다. */
    static BigDecimal tick(MarketRules rules, Symbol symbol, BigDecimal price) {
        return rules.tickSize(symbol, price);
    }
}
