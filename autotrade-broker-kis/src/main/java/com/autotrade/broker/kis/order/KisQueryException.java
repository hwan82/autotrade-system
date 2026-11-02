package com.autotrade.broker.kis.order;

import java.io.IOException;

/** 계좌 조회 실패. 메시지에는 TR과 결과 코드, 고정 안내만 넣는다(응답 원문·계좌번호 없음). */
public final class KisQueryException extends IOException {

    private static final long serialVersionUID = 1L;
    private final boolean rateLimited;

    KisQueryException(String message, boolean rateLimited) {
        super(message);
        this.rateLimited = rateLimited;
    }

    /** 초당 호출 한도 초과면 true. 호출자가 지수 백오프한다(D-054 R2, 장애표 #14). 이 클래스는 다시 부르지 않는다. */
    public boolean rateLimited() {
        return rateLimited;
    }
}
