package com.autotrade.strategy;

/**
 * 매매 스타일(D-006). 한 프로필은 한 스타일만 갖는다(스타일 혼합 금지).
 *
 * <p>이름이 프로필 해시에 들어가므로 기존 이름과 순서를 바꾸지 않고 새 값은 맨 뒤에 더한다(ADR-007 2.3). {@code ordinal}은
 * 어디에도 저장하지 않는다.
 */
public enum TradingStyle {
    INTRADAY,
    SWING,
    /** 중기(D-041 3번, STR-18). 검증 규칙과 로직은 전략 카탈로그(배치표 2번)가 더한다. 지금 카탈로그 로직은 받지 않는다. */
    POSITION
}
