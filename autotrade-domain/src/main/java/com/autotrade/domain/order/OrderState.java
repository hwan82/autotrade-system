package com.autotrade.domain.order;

/**
 * 주문 상태. 집합은 기획서 ORD-07(D-023)과 같다. 전이 규칙(허용 전이표, 상태머신)은 가상·실전이 같은 코드로
 * 공유하며 별도 작업에서 정의한다. 지금은 집합과 종결 여부만 정한다.
 *
 * <ul>
 *   <li>{@link #NEW}: 시스템 안에서 만들어졌고 아직 증권사(가상 거래소)로 보내지 않았다.</li>
 *   <li>{@link #SUBMITTING}: 보냈고 접수·거절 응답을 기다린다.</li>
 *   <li>{@link #ACCEPTED}: 증권사(가상 거래소)가 접수했다. 주문번호가 있다.</li>
 *   <li>{@link #PARTIALLY_FILLED}, {@link #FILLED}: 일부·전량 체결.</li>
 *   <li>{@link #CANCEL_REQUESTED}: 취소를 보냈고 확인을 기다린다. 그 사이 체결이 날 수 있으므로 종결이 아니다.</li>
 *   <li>{@link #CANCELED}: 취소 확정(사용자·시스템 취소, 체결 직전 현금 부족으로 남은 잔량 정리 등).</li>
 *   <li>{@link #REJECTED}: 접수 거절(증권사 거절, 출구에서 막힘 포함).</li>
 *   <li>{@link #EXPIRED}: 유효 기간이 끝나 소멸(당일 유효 주문의 장 마감 등). 취소 요청 없이 끝났다는 점이 CANCELED와 다르다.</li>
 *   <li>{@link #UNKNOWN}: 응답 시간 초과 등으로 접수 여부를 모른다. 증권사 상태 코드를 매핑하지 못한 경우도 여기다.</li>
 * </ul>
 *
 * <p>{@link #UNKNOWN}에서는 재주문하지 않고 반드시 조회부터 한다(프로젝트 지침 4장 2항). 그래서 종결이 아니다.
 */
public enum OrderState {
    NEW,
    SUBMITTING,
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_REQUESTED,
    CANCELED,
    REJECTED,
    EXPIRED,
    UNKNOWN;

    /** 더 바뀌지 않는 상태(체결·취소·거절·만료). 미체결·대기·미확정({@link #UNKNOWN})은 종결이 아니다. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED || this == EXPIRED;
    }
}
