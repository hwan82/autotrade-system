package com.autotrade.sim.testing;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트용 KRX 규칙. 호가 단위는 2023-01-25 주식 호가 단위표(2단계 KrxMarketRules와 같은 구간)이고, 정규장 판단은
 * 캘린더 없이 항상 참이다. 2단계 PR 머지 후 실제 KrxMarketRules로 바꿀 수 있다.
 */
public final class TestKrxRules implements MarketRules {

    public static final TestKrxRules INSTANCE = new TestKrxRules();

    private static final String[][] TICKS = {
        {"2000", "1"}, {"5000", "5"}, {"20000", "10"}, {"50000", "50"}, {"200000", "100"}, {"500000", "500"},
    };

    @Override
    public Market market() {
        return Market.KRX;
    }

    @Override
    public ZoneId zone() {
        return ZoneId.of("Asia/Seoul");
    }

    @Override
    public boolean isRegularSession(Instant instant) {
        return true;
    }

    @Override
    public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
        for (String[] row : TICKS) {
            if (price.compareTo(new BigDecimal(row[0])) < 0) {
                return new BigDecimal(row[1]);
            }
        }
        return new BigDecimal("1000");
    }
}
