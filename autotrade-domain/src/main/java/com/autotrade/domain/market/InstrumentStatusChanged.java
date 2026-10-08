package com.autotrade.domain.market;

import java.time.Instant;
import java.util.Objects;

/** 종목 거래 상태 변경 이벤트. */
public record InstrumentStatusChanged(
        Symbol symbol, long sequence, Instant exchangeTime, Instant receivedAt, InstrumentStatus status)
        implements MarketEvent {

    public InstrumentStatusChanged {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(exchangeTime, "exchangeTime");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(status, "status");
    }
}
