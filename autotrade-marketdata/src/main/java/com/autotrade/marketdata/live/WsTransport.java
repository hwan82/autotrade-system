package com.autotrade.marketdata.live;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/** WebSocket 접속 포트. 운영은 {@link JdkWsTransport}, 테스트는 가짜 구현을 쓴다. */
public interface WsTransport {

    /** 접속한다. 실패하면 예외로 끝나는 future를 돌려준다. 핸들러 호출은 연결마다 순서대로 한 번에 하나씩이다. */
    CompletableFuture<WsConnection> connect(URI endpoint, WsHandler handler);
}
