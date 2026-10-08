package com.autotrade.sim.run;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import java.util.List;
import java.util.Objects;

/** 메모리에 든 시세 스냅샷. 테스트 데이터와 녹화 어댑터가 쓴다. */
public record InMemoryMarketDataSet(String version, Market market, List<MarketEvent> events) implements MarketDataSet {

    public InMemoryMarketDataSet {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(market, "market");
        events = List.copyOf(events);
        long prev = Long.MIN_VALUE;
        for (MarketEvent e : events) {
            if (e.sequence() <= prev) {
                throw new IllegalArgumentException("이벤트가 sequence 오름차순이 아닙니다: " + prev + " -> " + e.sequence());
            }
            if (e.symbol().market() != market) {
                throw new IllegalArgumentException("다른 시장의 이벤트가 섞여 있습니다: " + e.symbol());
            }
            prev = e.sequence();
        }
    }
}
