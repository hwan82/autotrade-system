package com.autotrade.marketdata;

import com.autotrade.domain.market.MarketEvent;

/**
 * 시세 이벤트 수신자. 한 구독의 이벤트는 한 스레드에서 {@code sequence} 순서대로 호출된다.
 * 수신자는 빨리 돌아와야 하며, 느린 처리는 자기 큐로 넘긴다(생산자는 기다리지 않는다, broker-and-fault-design.md 5장).
 */
@FunctionalInterface
public interface MarketEventListener {

    void onEvent(MarketEvent event);
}
