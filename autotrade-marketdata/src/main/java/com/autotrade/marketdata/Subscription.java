package com.autotrade.marketdata;

import com.autotrade.domain.market.Symbol;
import java.util.SortedSet;

/** 활성 구독. {@link #close()} 후에는 이벤트가 더 오지 않는다. 여러 번 닫아도 안전하다. */
public interface Subscription extends AutoCloseable {

    SortedSet<Symbol> symbols();

    @Override
    void close();
}
