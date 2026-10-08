package com.autotrade.sim.exchange;

import java.time.Duration;
import java.util.Objects;

/**
 * 가상 체결 설정(SIM-06). 결과에 영향을 주므로 실험 결과에 {@link #id()}를 남기고, 같은 ID끼리만 성과를 비교한다.
 *
 * @param orderLatency 신호 시점(이벤트 수신 시각)부터 주문이 거래소에서 유효해질 때까지의 지연. 이 시간이 지나고,
 *     신호 이벤트보다 sequence가 큰 이벤트부터 체결에 쓴다. 기본 {@link #DEFAULT_LATENCY} `[가정]`
 */
public record ExecutionSettings(Duration orderLatency) {

    /** 체결 규칙 버전. 규칙을 바꾸면 올린다(같은 데이터·프로필이라도 결과가 달라지므로). */
    public static final String RULES_VERSION = "BOOK-V1";

    /**
     * 기본 주문 지연 0.5초 `[가정]`. 실측 근거가 없어 MON-04 목표(1.5초)보다 낙관적인 값으로 두고,
     * 승격 전 지연 민감도(0·1.5·2초, STR-13)로 다시 돌린다. 6단계 실측 후 조정.
     */
    public static final Duration DEFAULT_LATENCY = Duration.ofMillis(500);

    public ExecutionSettings {
        Objects.requireNonNull(orderLatency, "orderLatency");
        if (orderLatency.isNegative()) {
            throw new IllegalArgumentException("주문 지연은 음수일 수 없습니다: " + orderLatency);
        }
    }

    public static ExecutionSettings defaults() {
        return new ExecutionSettings(DEFAULT_LATENCY);
    }

    /** 예: {@code BOOK-V1;latency=PT0.5S}. */
    public String id() {
        return RULES_VERSION + ";latency=" + orderLatency;
    }
}
