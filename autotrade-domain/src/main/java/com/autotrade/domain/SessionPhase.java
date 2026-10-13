package com.autotrade.domain;

/**
 * 거래소 매매 단계({@link MarketRules#sessionPhase}). 가상 거래소가 이 단계로 체결 방식을 고른다(ADR-004 2.2 12번, D-069).
 *
 * <ul>
 *   <li>{@link #OPENING_AUCTION}: 시가 단일가 접수 구간. 호가는 예상 호가이며 체결이 없다. 접수분은 개장 순간 한 가격에 체결된다.</li>
 *   <li>{@link #CONTINUOUS}: 접속 매매(연속 체결).</li>
 *   <li>{@link #CLOSING_AUCTION}: 종가 단일가 접수 구간. 접수분은 폐장 순간 한 가격에 체결된다.</li>
 *   <li>{@link #CLOSED}: 정규장 밖(장전 시간외 전, 폐장 뒤 시간외, 휴장일). 정규장 주문은 체결되지 않는다.</li>
 * </ul>
 */
public enum SessionPhase {
    CLOSED,
    OPENING_AUCTION,
    CONTINUOUS,
    CLOSING_AUCTION;

    /** 단일가 접수 구간인지. */
    public boolean isAuction() {
        return this == OPENING_AUCTION || this == CLOSING_AUCTION;
    }
}
