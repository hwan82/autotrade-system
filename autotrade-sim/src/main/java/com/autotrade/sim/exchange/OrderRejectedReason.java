package com.autotrade.sim.exchange;

/** 가상 거래소가 주문을 거절·취소한 사유 코드. 거래 기록과 리포트에 남긴다. */
public enum OrderRejectedReason {
    /** 계좌 시장과 다른 종목. */
    WRONG_MARKET,
    /** 수량이 주문 수량 단위의 배수가 아님. */
    QUANTITY_STEP,
    /** 지정가가 호가 단위에 맞지 않음. */
    TICK_SIZE,
    /** 매수 금액(비용 포함 추정)이 주문 가능 현금보다 큼. */
    INSUFFICIENT_CASH,
    /** 매도 수량이 (보유 − 미체결 매도)보다 큼. 공매도 없음. */
    INSUFFICIENT_POSITION,
    /** 시장가 매수 금액을 추정할 시세(호가·체결가)가 아직 없음. */
    NO_REFERENCE_PRICE,
    /** 체결 시점 현금이 모자라 잔량을 취소함. */
    CASH_SHORT_AT_FILL,
    /** 장 마감(거래일 종료)으로 남은 잔량이 만료됨(주문 상태 EXPIRED). 국내·미국 정규장 주문은 당일 유효. */
    DAY_END
}
