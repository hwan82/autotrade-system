package com.autotrade.domain.market;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 호가 스냅샷. {@code asks}는 낮은 가격부터, {@code bids}는 높은 가격부터 정렬되어 있어야 한다.
 */
public record OrderBookSnapshot(
        Symbol symbol,
        long sequence,
        Instant exchangeTime,
        Instant receivedAt,
        List<PriceLevel> asks,
        List<PriceLevel> bids)
        implements MarketEvent {

    public OrderBookSnapshot {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(exchangeTime, "exchangeTime");
        Objects.requireNonNull(receivedAt, "receivedAt");
        asks = List.copyOf(asks);
        bids = List.copyOf(bids);
        requireSorted(asks, true);
        requireSorted(bids, false);
    }

    private static void requireSorted(List<PriceLevel> levels, boolean ascending) {
        for (int i = 1; i < levels.size(); i++) {
            int cmp = levels.get(i - 1).price().compareTo(levels.get(i).price());
            if (ascending ? cmp >= 0 : cmp <= 0) {
                throw new IllegalArgumentException((ascending ? "매도" : "매수") + " 호가 정렬이 올바르지 않습니다");
            }
        }
    }
}
