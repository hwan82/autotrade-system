package com.autotrade.marketdata.live;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.SortedSet;

/**
 * 증권사별 실시간 메시지 형식. 플러그인(kiwoom, kis, toss)이 구현하고, 연결·재연결·순번·누락 표시는
 * {@link StreamingMarketDataSource}가 공통으로 한다(ADR-003 2.1). 구현은 주문 메시지를 만들지 않는다.
 *
 * <p>구현은 연결 하나에 대해 상태를 갖지 않는 것이 좋다. 연결마다 {@link #openingMessages()}가 새로 불린다.
 */
public interface StreamProtocol {

    URI endpoint();

    /** 이 소스가 다루는 시장. 구독 종목은 모두 이 시장이어야 한다. */
    Market market();

    /** 구독할 수 있는 종목 수 상한(체결+호가를 함께 받는 기준). */
    int maxSymbols();

    /**
     * 접속 직후 보낼 메시지(로그인 등). 토큰 발급처럼 블로킹 I/O를 해도 된다(연결 스레드에서 불린다).
     * 실패하면 예외를 던지고, 소스가 재연결 대기로 넘긴다. 예외 메시지에 비밀값을 넣지 않는다.
     */
    List<String> openingMessages() throws Exception;

    /** {@code true}면 {@link Decoded.Control#LOGIN_OK}를 받은 뒤에 구독을 등록한다. */
    boolean requiresLoginAck();

    /** 구독 등록 메시지. 비어 있으면 빈 목록. */
    List<String> subscribeMessages(SortedSet<Symbol> symbols);

    /** 구독 해지 메시지. 비어 있으면 빈 목록. */
    List<String> unsubscribeMessages(SortedSet<Symbol> symbols);

    /** 수신 메시지 해석. 형식 오류는 예외 대신 {@link Decoded#ignored}로 돌려준다. */
    Decoded decode(String text, Instant receivedAt);

    /** 인증 실패 뒤 다음 연결에서 토큰을 새로 받게 한다. */
    default void invalidateCredentials() {}
}
