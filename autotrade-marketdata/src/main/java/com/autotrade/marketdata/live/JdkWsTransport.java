package com.autotrade.marketdata.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * JDK 내장 {@link java.net.http.WebSocket} 전송. 외부 라이브러리를 쓰지 않는다(순수 Java, ADR-003 2.1).
 * 보내기는 이전 전송이 끝난 뒤에 이어 붙인다(JDK WebSocket은 동시에 두 전송을 허용하지 않는다).
 */
public final class JdkWsTransport implements WsTransport {

    private final HttpClient client;
    private final Duration connectTimeout;

    public JdkWsTransport(HttpClient client, Duration connectTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    }

    @Override
    public CompletableFuture<WsConnection> connect(URI endpoint, WsHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return client.newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .buildAsync(endpoint, new Listener(handler))
                .thenApply(ws -> new Connection(ws, handler));
    }

    private static final class Connection implements WsConnection {
        private final WebSocket socket;
        private final WsHandler handler;
        private CompletableFuture<?> chain = CompletableFuture.completedFuture(null);

        Connection(WebSocket socket, WsHandler handler) {
            this.socket = socket;
            this.handler = handler;
        }

        @Override
        public synchronized void send(String text) {
            // 전송 실패(구독 메시지 유실 등)를 삼키지 않는다: 연결 오류로 알려 소스가 끊고 다시 붙게 한다
            chain = chain.handle((ok, err) -> null)
                    .thenCompose(ignored -> socket.sendText(text, true))
                    .whenComplete((ok, err) -> {
                        if (err != null) {
                            handler.onError(err);
                        }
                    });
        }

        @Override
        public void close() {
            if (!socket.isOutputClosed()) {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            }
            socket.abort();
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final WsHandler handler;
        private final StringBuilder partial = new StringBuilder();

        Listener(WsHandler handler) {
            this.handler = handler;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                handler.onText(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handler.onClosed(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handler.onError(error);
        }
    }
}
