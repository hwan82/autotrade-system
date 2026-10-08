package com.autotrade.domain.market;

import java.time.Instant;

/**
 * 불변 시세 이벤트. 시세 소스가 만들고 실전 엔진과 실험실이 읽기만 한다.
 *
 * <p>{@code sequence}는 한 소스 스트림 안에서 모든 종목에 걸친 총순서(단조 증가)이며 리플레이 순서의 기준이다.
 * 소스끼리(FAILOVER 전환, SHARDED 병합)는 번호를 비교할 수 없으므로, 병합 순서는 시세 게이트웨이 녹화기가 매기는
 * 별도 번호로 정한다(ADR-002 2.3, 녹화 순번). 누락 구간은 {@link DataGap}으로 표시한다. 일봉은 이벤트가 아니라
 * {@code autotrade-marketdata}의 일봉 소스로 따로 읽는다(ADR-002 2.6).
 * {@code exchangeTime}은 거래소 시각, {@code receivedAt}은 이 시스템이 받은 시각이다.
 * 시뮬레이션에서 "T 시점에 알 수 있었던 정보"는 {@code receivedAt} 기준으로 판단한다.
 */
public sealed interface MarketEvent permits TradeTick, OrderBookSnapshot, InstrumentStatusChanged, DataGap {

    Symbol symbol();

    long sequence();

    Instant exchangeTime();

    Instant receivedAt();
}
