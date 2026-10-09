package com.autotrade.marketdata.live;

/** WebSocket 수신 콜백. 한 연결의 콜백은 겹치지 않는다. */
public interface WsHandler {

    /** 완성된 텍스트 메시지 하나(조각 프레임은 전송 계층이 합친다). */
    void onText(String text);

    void onClosed(int statusCode, String reason);

    /**
     * 수신 오류나 전송 실패. 전송 실패는 {@link WsConnection#send}를 부른 스레드에서, {@code send}가 돌아오기 전에
     * 불릴 수 있다(이미 실패한 전송). 구현은 이 콜백에서 호출자의 잠금을 쥔 채 다른 객체로 알림을 내보내지 않아야 한다.
     */
    void onError(Throwable error);
}
