package com.autotrade.marketdata.live;

/** 열린 WebSocket 연결. 보내기는 순서를 지키며 블로킹하지 않는다. */
public interface WsConnection {

    void send(String text);

    /** 닫는다. 여러 번 불러도 안전하다. 닫은 뒤 핸들러는 더 불리지 않을 수도, {@code onClosed}가 한 번 불릴 수도 있다. */
    void close();
}
