package com.autotrade.risk.guard;

/** Guard가 허용하지 않는 조작(OFF 계좌의 주문 세션 허가 요청, 규칙에 없는 전이)을 시도했다. */
public final class GuardClosedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    GuardClosedException(String message) {
        super(message);
    }
}
