package com.autotrade.marketdata;

/**
 * 증권사 실시간 시세 소스(ADR-003 2.1). {@link MarketDataSource} 계약에 연결 수명과 상태 알림을 더한다.
 *
 * <p>계약
 * <ul>
 *   <li>{@link #start()}는 한 번만 부른다. 접속·재연결은 소스가 스스로 하고, 끊긴 구간은 재연결 뒤
 *       {@link com.autotrade.domain.market.DataGap}으로 구독 종목마다 한 번씩 알린다.</li>
 *   <li>구독은 {@code start()} 전후 언제든 할 수 있다. 연결되면 현재 구독 전체를 다시 등록한다.</li>
 *   <li>상태 조회는 블로킹하지 않는다.</li>
 *   <li>주문 API를 호출하지 않는다(안전 불변식 8).</li>
 * </ul>
 */
public interface LiveMarketDataSource extends MarketDataSource, AutoCloseable {

    void start();

    ConnectionState state();

    void addStateListener(SourceStateListener listener);

    /**
     * 현재(또는 마지막) 연결에서 서버 메시지를 마지막으로 받은 시각. 끊긴 소스의 종목을 옮길 때 공백 시작으로 쓴다.
     * 접속 전이면 빈 값.
     */
    /**
     * 연속 인증 실패로 자격 증명이 거부된 것으로 보는지. 플러그인은 이때 DEGRADED로 알리고 게이트웨이는 배분에서 뺀다.
     * 인증이 다시 확인되면(로그인 응답 또는 시세 수신) 풀린다.
     */
    default boolean credentialsRejected() {
        return false;
    }

    default java.util.Optional<java.time.Instant> lastReceivedAt() {
        return java.util.Optional.empty();
    }

    @Override
    void close();
}
