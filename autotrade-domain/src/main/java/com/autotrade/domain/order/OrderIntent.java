package com.autotrade.domain.order;

/**
 * 주문 의도. 계좌별 직렬 큐의 우선순위(손절 우선)와, 신규 진입만 막고 청산은 허용해야 하는 상황
 * (DEGRADED, 서킷브레이커 1단계, 실전 비활성화)의 판단 근거다. 판단 규칙 자체는 risk-ops 작업에서 정의한다.
 */
public enum OrderIntent {
    /** 신규 진입 또는 추가 매수. */
    ENTRY,
    /** 익절·전략 청산. */
    EXIT,
    /** 손절. 큐에서 가장 먼저 처리한다. */
    STOP_LOSS;

    /** 보유 종목 보호를 위한 주문인지(청산·손절). */
    public boolean isProtective() {
        return this != ENTRY;
    }
}
