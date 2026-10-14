package com.autotrade.marketdata.recording;

import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.TradeTick;

/** 이벤트의 순번만 바꾼 사본. 녹화 순번과 소스 순번을 오갈 때 쓴다. */
public final class EventSequences {

    private EventSequences() {}

    public static MarketEvent withSequence(MarketEvent e, long seq) {
        return switch (e) {
            case TradeTick t -> new TradeTick(t.symbol(), seq, t.exchangeTime(), t.receivedAt(), t.price(), t.quantity());
            case OrderBookSnapshot b -> new OrderBookSnapshot(b.symbol(), seq, b.exchangeTime(), b.receivedAt(), b.asks(), b.bids());
            case InstrumentStatusChanged s -> new InstrumentStatusChanged(s.symbol(), seq, s.exchangeTime(), s.receivedAt(), s.status());
            case DataGap g -> new DataGap(g.symbol(), seq, g.exchangeTime(), g.receivedAt(), g.gapStart(), g.gapEnd(), g.reason());
        };
    }
}
