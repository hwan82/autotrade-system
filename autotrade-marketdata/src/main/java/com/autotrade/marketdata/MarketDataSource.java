package com.autotrade.marketdata;

import com.autotrade.domain.market.Symbol;
import java.util.Set;

/**
 * 실시간·리플레이 시세 소스(푸시 방식). 구현: LiveQuoteSource(실전 API 실시간, 시세 게이트웨이 프로세스),
 * RecordedReplaySource(녹화 리플레이). 일봉 조회(기간 풀 방식, 수정주가·상장폐지 포함)는 별도 인터페이스로
 * 2단계 ADR에서 정한다. 구독 증분 갱신, 연결 상태 콜백, 누락 구간 표시도 2단계 ADR 범위다.
 *
 * <p>계약
 * <ul>
 *   <li>시세 소스는 주문 API를 호출하지 않는다(안전 불변식 8).</li>
 *   <li>한 소스가 내보내는 {@code MarketEvent.sequence()}는 모든 종목에 걸쳐 단조 증가한다.
 *       리플레이는 녹화 순서를 그대로 재현한다.</li>
 *   <li>{@link #maxSubscriptions()}를 넘는 구독 요청은 {@link IllegalArgumentException}으로 거절한다
 *       (한도 수치는 Q-001 결과로 채운다).</li>
 *   <li>연결 끊김·누락 구간은 이벤트를 지어내지 않고, 품질 메타데이터로 표시한다(별도 계약, 2단계).</li>
 * </ul>
 */
public interface MarketDataSource {

    /** 로그·녹화 메타데이터에 남길 소스 이름. 예: {@code KIWOOM-WS}, {@code REPLAY-20261008}. */
    String sourceId();

    /** 동시에 구독할 수 있는 종목 수 상한. */
    int maxSubscriptions();

    Subscription subscribe(Set<Symbol> symbols, MarketEventListener listener);
}
