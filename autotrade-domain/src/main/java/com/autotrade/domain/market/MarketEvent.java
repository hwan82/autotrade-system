package com.autotrade.domain.market;

import java.time.Instant;

/**
 * 불변 시세 이벤트. 시세 소스가 만들고 실전 엔진과 실험실이 읽기만 한다.
 *
 * <p>{@code sequence}는 소스가 매기는 단조 증가 번호이며 리플레이 순서의 기준이다.
 * {@code exchangeTime}은 거래소 시각, {@code receivedAt}은 이 시스템이 받은 시각이다.
 * 시뮬레이션에서 "T 시점에 알 수 있었던 정보"는 {@code receivedAt} 기준으로 판단한다.
 */
public sealed interface MarketEvent permits TradeTick, OrderBookSnapshot, InstrumentStatusChanged {

    Symbol symbol();

    long sequence();

    Instant exchangeTime();

    Instant receivedAt();
}
