package com.autotrade.sim.run;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import java.util.Objects;

/**
 * 체결되지 못한 신호·주문 기록. 리스크 검증 거절(블랙리스트 포함)과 가상 거래소 거절·취소를 모두 남긴다.
 *
 * @param stage {@code RISK}(리스크 검증 거절) 또는 {@code EXCHANGE}(거래소 접수 거절·잔량 취소)
 * @param code 리스크 규칙 ID 또는 {@link com.autotrade.sim.exchange.OrderRejectedReason} 이름
 */
public record OrderRejection(long eventSeq, IdempotencyKey key, Symbol symbol, String stage, String code, String detail) {

    public OrderRejection {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(code, "code");
        detail = detail == null ? "" : detail;
    }
}
