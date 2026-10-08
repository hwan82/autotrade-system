package com.autotrade.marketdata;

import com.autotrade.domain.market.Symbol;
import java.util.Set;

/**
 * 실시간·리플레이 시세 소스(푸시 방식). 구현: {@link LiveMarketDataSource}(실전 API 실시간, 시세 게이트웨이 프로세스,
 * {@code live.StreamingMarketDataSource}), {@code recording.RecordedReplaySource}(녹화 리플레이).
 * 일봉은 {@code daily.DailyBarSource}로 따로 읽는다. 연결 상태와 누락 구간 표시는 ADR-002.
 *
 * <p>계약
 * <ul>
 *   <li>시세 소스는 주문 API를 호출하지 않는다(안전 불변식 8).</li>
 *   <li>한 소스가 내보내는 {@code MarketEvent.sequence()}는 모든 종목에 걸쳐 단조 증가한다.
 *       리플레이는 녹화 순서를 그대로 재현한다.</li>
 *   <li>모든 구독의 종목 합집합이 {@link #maxSubscriptions()}를 넘는 구독 요청은 {@link IllegalArgumentException}으로
 *       거절한다(한도 수치: docs/research/market-data-limits.md 1장).</li>
 *   <li>연결 끊김·누락 구간은 가격 이벤트를 지어내지 않고 {@link com.autotrade.domain.market.DataGap}으로 표시한다.</li>
 * </ul>
 */
public interface MarketDataSource {

    /** 로그·녹화 메타데이터에 남길 소스 이름. 예: {@code KIWOOM-WS}, {@code REPLAY-20261008}. */
    String sourceId();

    /** 동시에 구독할 수 있는 종목 수 상한. */
    int maxSubscriptions();

    Subscription subscribe(Set<Symbol> symbols, MarketEventListener listener);
}
