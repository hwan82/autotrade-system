package com.autotrade.risk.guard;

/**
 * Guard가 허용하지 않는 조작(OFF 계좌의 주문 세션 허가 요청, 규칙에 없는 전이)을 시도했다. 생성자는 이 패키지 안에서만 부르므로
 * 다른 패키지가 이 예외(와 하위 타입)를 만들어 Guard 판정처럼 보이게 할 수 없다.
 */
public class GuardClosedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    GuardClosedException(String message) {
        super(message);
    }

    GuardClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
