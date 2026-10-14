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

    /**
     * 체결 규칙 버전. 규칙을 바꾸면 올린다(같은 데이터·프로필이라도 결과가 달라지므로).
     * BOOK-V2(D-068): 시세 공백({@code DataGap})에서 그 종목 호가와 대기 지정가의 앞선 잔량을 버린다. 공백 없는 데이터의
     * 결과는 BOOK-V1과 같다(골든 테스트로 확인).
     * BOOK-V3(D-069): 매매 단계({@code MarketRules.sessionPhase})를 따른다. 접속 매매 이벤트로만 연속 체결하고, 단일가 접수 구간
     * 주문은 구간이 끝난 뒤 첫 체결가로 일괄 체결하며(KRX 시가는 종목별 그날 첫 체결), 장 밖 이벤트로는 체결하지 않는다.
     * 이벤트 시각이 합성인 데이터(일봉 경로)는 매매 단계를 보지 않는다. 단일가 구간이 없는 규칙(정규장 내내
     * 접속 매매)으로 돌린 결과는 BOOK-V2와 같다(골든 테스트로 확인).
     * BOOK-V4(D-074): 평가 가격(마지막 체결가)은 정규장 체결(접속 매매, 시가·종가 단일가 체결)로만 갱신하고, 종가 단일가 체결은
     * 종가 단일가 구간이 끝난 뒤 짧은 시간 안의 첫 체결로만 본다. 그날 그 종목 첫 이벤트가 개장 뒤 한참 지나 오면(장중 시작
     * 녹화) 시가 단일가 체결이 이미 지난 것으로 본다. 장 밖 체결과 장중 시작이 없는 데이터의 결과는 BOOK-V3과 같다.
     */
    public static final String RULES_VERSION = "BOOK-V4";

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

    /** 예: {@code BOOK-V3;latency=PT0.5S}. */
    public String id() {
        return RULES_VERSION + ";latency=" + orderLatency;
    }
}
