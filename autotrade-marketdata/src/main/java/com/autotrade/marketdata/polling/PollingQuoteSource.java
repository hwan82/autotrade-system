package com.autotrade.marketdata.polling;

import com.autotrade.domain.market.Symbol;
import java.io.IOException;
import java.util.Map;
import java.util.SortedSet;

/**
 * 폴링 현재가 소스(능력 POLLING_QUOTE, D-024). 실시간 소스가 모두 끊겨도 보유 종목 손절 감시를 이어 가는 보호 경로다
 * (broker-and-fault-design.md 장애표 2번). 부르는 쪽(실전 엔진 보호 감시)이 주기를 정하고, 레이트리밋은 구현이 지킨다.
 * 주문 API를 호출하지 않는다.
 */
public interface PollingQuoteSource {

    String sourceId();

    /** 한 번 호출에 물을 수 있는 종목 수 상한. */
    int maxSymbolsPerCall();

    /** 현재가를 묻는다. 응답에 없는 종목은 결과에서 빠진다(값을 지어내지 않는다). */
    Map<Symbol, LastPrice> poll(SortedSet<Symbol> symbols) throws IOException, InterruptedException;
}
