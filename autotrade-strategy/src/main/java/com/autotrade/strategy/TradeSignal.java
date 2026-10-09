package com.autotrade.strategy;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 전략이 낸 매매 신호. 실행기가 멱등 키를 붙여 {@link com.autotrade.domain.order.OrderRequest}로 만들고
 * 블랙리스트·리스크 검증을 거쳐 주문한다. 검증 규칙은 OrderRequest와 같다(공매도 없음, 지정가는 가격 필수).
 *
 * @param limitPrice LIMIT이면 필수이고 호가 단위에 맞아야 한다(안 맞으면 거래소가 거절). MARKET이면 null
 */
public record TradeSignal(
        Symbol symbol, Side side, OrderIntent intent, OrderType type, BigDecimal limitPrice, long quantity) {

    public TradeSignal {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(type, "type");
        if (intent == OrderIntent.ENTRY && side == Side.SELL) {
            throw new IllegalArgumentException("매도 신호의 의도는 EXIT 또는 STOP_LOSS여야 합니다");
        }
        if (intent.isProtective() && side == Side.BUY) {
            throw new IllegalArgumentException("매수 신호는 청산·손절이 될 수 없습니다");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 0보다 커야 합니다: " + quantity);
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("지정가 신호에는 0보다 큰 가격이 필요합니다");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("시장가 신호에는 가격을 넣지 않습니다");
        }
    }

    public static TradeSignal marketBuy(Symbol symbol, long quantity) {
        return new TradeSignal(symbol, Side.BUY, OrderIntent.ENTRY, OrderType.MARKET, null, quantity);
    }

    public static TradeSignal marketSell(Symbol symbol, OrderIntent intent, long quantity) {
        return new TradeSignal(symbol, Side.SELL, intent, OrderType.MARKET, null, quantity);
    }

    public static TradeSignal limitBuy(Symbol symbol, BigDecimal price, long quantity) {
        return new TradeSignal(symbol, Side.BUY, OrderIntent.ENTRY, OrderType.LIMIT, price, quantity);
    }

    public static TradeSignal limitSell(Symbol symbol, OrderIntent intent, BigDecimal price, long quantity) {
        return new TradeSignal(symbol, Side.SELL, intent, OrderType.LIMIT, price, quantity);
    }
}
