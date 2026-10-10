package com.autotrade.marketdata.live;

/** 열린 WebSocket 연결. 보내기는 순서를 지키며 블로킹하지 않는다. */
public interface WsConnection {

    void send(String text);

    /**
     * WebSocket PONG 제어 프레임을 보낸다(RFC 6455 5.5.3, 요청 없는 PONG은 단방향 생존 신호). 내용은 UTF-8 125바이트 이하.
     * 보내기 순서는 {@link #send}와 같은 줄을 따른다. PONG을 쓰지 않는 연결(테스트 가짜)은 구현하지 않아도 된다.
     */
    default void sendPong(String payload) {
        throw new UnsupportedOperationException("PONG 프레임을 보낼 수 없는 연결");
    }

    /** 닫는다. 여러 번 불러도 안전하다. 닫은 뒤 핸들러는 더 불리지 않을 수도, {@code onClosed}가 한 번 불릴 수도 있다. */
    void close();
}
