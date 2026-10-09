package com.autotrade.marketdata;

/** 시세 소스 연결 상태 변화 알림. 빨리 돌아와야 한다(소스의 내부 잠금 밖에서 호출되지만 같은 스레드일 수 있다). */
@FunctionalInterface
public interface SourceStateListener {

    void onStateChanged(String sourceId, ConnectionState state);
}
