package com.autotrade.domain.market;

import java.time.Instant;
import java.util.Objects;

/**
 * 시세 누락 구간 표시(MD-05). 가격 이벤트를 지어내지 않고, "이 종목은 {@code gapStart}~{@code gapEnd} 사이에
 * 받지 못한 이벤트가 있을 수 있다"는 품질 메타데이터만 전한다(ADR-002 2.2).
 *
 * <p>구간이 끝난 뒤(재연결, 큐 회복)에 한 번 나온다. {@code exchangeTime}과 {@code receivedAt}은 이 표시를 만든 시각이며
 * {@code gapEnd}와 같다. 시뮬레이션은 이 구간에 걸친 체결·신호를 결과에 표시한다.
 *
 * @param gapStart 마지막으로 정상 수신을 확인한 시각
 * @param gapEnd 정상 수신을 다시 확인한 시각
 */
public record DataGap(
        Symbol symbol,
        long sequence,
        Instant exchangeTime,
        Instant receivedAt,
        Instant gapStart,
        Instant gapEnd,
        GapReason reason)
        implements MarketEvent {

    public DataGap {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(exchangeTime, "exchangeTime");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(gapStart, "gapStart");
        Objects.requireNonNull(gapEnd, "gapEnd");
        Objects.requireNonNull(reason, "reason");
        if (gapEnd.isBefore(gapStart)) {
            throw new IllegalArgumentException("누락 구간 끝이 시작보다 앞입니다: " + gapStart + " -> " + gapEnd);
        }
    }

    /** 누락 원인. */
    public enum GapReason {
        /** 증권사 연결 끊김 또는 무응답으로 재연결했다. */
        DISCONNECTED,
        /** 수신자 큐가 가득 차서 이벤트를 버렸다(생산자는 기다리지 않는다). */
        CONSUMER_OVERFLOW,
        /** 녹화 큐가 가득 차서 녹화에서 이벤트를 버렸다(RSK-07 녹화 축소). */
        RECORDER_OVERFLOW,
        /** 시세 소스를 다른 증권사로 바꿨다(FAILOVER·재배분). 전환 사이 이벤트가 빠졌을 수 있다. */
        SOURCE_SWITCH
    }
}
