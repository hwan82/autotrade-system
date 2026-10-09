package com.autotrade.marketdata.live;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 테스트용 전송. 접속은 바로 성공(또는 {@link #failNext}면 실패)하고, 서버 쪽에서 메시지를 밀어 넣을 수 있다. */
final class FakeTransport implements WsTransport {

    final List<Conn> connections = new ArrayList<>();
    private int failNext;

    void failNext(int n) {
        failNext = n;
    }

    @Override
    public CompletableFuture<WsConnection> connect(URI endpoint, WsHandler handler) {
        if (failNext > 0) {
            failNext--;
            return CompletableFuture.failedFuture(new java.io.IOException("refused"));
        }
        Conn c = new Conn(handler);
        connections.add(c);
        return CompletableFuture.completedFuture(c);
    }

    Conn last() {
        return connections.get(connections.size() - 1);
    }

    static final class Conn implements WsConnection {
        final WsHandler handler;
        final List<String> sent = new ArrayList<>();
        boolean closed;
        /** true면 보내기가 실패하고, JDK의 이미 실패한 전송처럼 {@code send}를 부른 스레드에서 바로 오류를 알린다. */
        boolean failSends;

        Conn(WsHandler handler) {
            this.handler = handler;
        }

        @Override
        public void send(String text) {
            if (failSends) {
                handler.onError(new java.io.IOException("Output closed"));
                return;
            }
            sent.add(text);
        }

        @Override
        public void close() {
            closed = true;
        }

        void push(String text) {
            handler.onText(text);
        }

        void serverClose() {
            handler.onClosed(1006, "abnormal");
        }
    }
}
