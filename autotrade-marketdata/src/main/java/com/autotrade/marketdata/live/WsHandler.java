package com.autotrade.marketdata.live;

/** WebSocket 수신 콜백. 한 연결의 콜백은 겹치지 않는다. */
public interface WsHandler {

    /** 완성된 텍스트 메시지 하나(조각 프레임은 전송 계층이 합친다). */
    void onText(String text);

    void onClosed(int statusCode, String reason);

    void onError(Throwable error);
}
