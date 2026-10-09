package com.autotrade.marketdata.live;

import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 증권사 메시지를 해석한 결과(순번과 수신 시각을 붙이기 전). 순번은 {@link StreamingMarketDataSource}가 한곳에서 매긴다.
 */
public sealed interface RawQuote {

    Symbol symbol();

    Instant exchangeTime();

    MarketEvent toEvent(long sequence, Instant receivedAt);

    record Trade(Symbol symbol, Instant exchangeTime, BigDecimal price, long quantity) implements RawQuote {
        public Trade {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(exchangeTime, "exchangeTime");
            Objects.requireNonNull(price, "price");
        }

        @Override
        public MarketEvent toEvent(long sequence, Instant receivedAt) {
            return new TradeTick(symbol, sequence, exchangeTime, receivedAt, price, quantity);
        }
    }

    record Book(Symbol symbol, Instant exchangeTime, List<PriceLevel> asks, List<PriceLevel> bids) implements RawQuote {
        public Book {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(exchangeTime, "exchangeTime");
            asks = List.copyOf(asks);
            bids = List.copyOf(bids);
        }

        @Override
        public MarketEvent toEvent(long sequence, Instant receivedAt) {
            return new OrderBookSnapshot(symbol, sequence, exchangeTime, receivedAt, asks, bids);
        }
    }

    record Status(Symbol symbol, Instant exchangeTime, InstrumentStatus status) implements RawQuote {
        public Status {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(exchangeTime, "exchangeTime");
            Objects.requireNonNull(status, "status");
        }

        @Override
        public MarketEvent toEvent(long sequence, Instant receivedAt) {
            return new InstrumentStatusChanged(symbol, sequence, exchangeTime, receivedAt, status);
        }
    }
}
