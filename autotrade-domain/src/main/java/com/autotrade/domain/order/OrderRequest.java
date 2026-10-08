package com.autotrade.domain.order;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 주문 요청(가상·실전 공통). 리스크 검증과 블랙리스트 필터를 통과한 뒤에만 {@link OrderGateway}로 간다.
 *
 * @param limitPrice LIMIT이면 필수, MARKET이면 null
 */
public record OrderRequest(
        IdempotencyKey key,
        AccountId account,
        Symbol symbol,
        Side side,
        OrderIntent intent,
        OrderType type,
        BigDecimal limitPrice,
        long quantity) {

    public OrderRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(type, "type");
        if (!key.code().equals(symbol.code()) || key.side() != side) {
            throw new IllegalArgumentException("멱등 키와 주문 내용이 다릅니다: " + key);
        }
        if (intent == OrderIntent.ENTRY && side == Side.SELL) {
            // 1단계는 공매도가 없으므로 매도는 항상 보유 청산이다
            throw new IllegalArgumentException("매도 주문의 의도는 EXIT 또는 STOP_LOSS여야 합니다");
        }
        if (intent.isProtective() && side == Side.BUY) {
            throw new IllegalArgumentException("매수 주문은 청산·손절이 될 수 없습니다");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 0보다 커야 합니다: " + quantity);
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("지정가 주문에는 0보다 큰 가격이 필요합니다");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("시장가 주문에는 가격을 넣지 않습니다");
        }
    }
}
