package com.autotrade.marketdata;

/** 실시간 시세 소스의 연결 상태(ADR-002 2.1). */
public enum ConnectionState {
    /** 만들었지만 {@code start()} 전. */
    NEW,
    /** 접속·인증 중. */
    CONNECTING,
    /** 인증과 구독 복구가 끝나 이벤트를 받는 중. */
    CONNECTED,
    /** 끊겨서 재연결을 기다리는 중. 이 사이 이벤트는 누락 구간으로 표시된다. */
    DISCONNECTED,
    /** {@code close()} 후. 다시 열지 않는다. */
    CLOSED
}
