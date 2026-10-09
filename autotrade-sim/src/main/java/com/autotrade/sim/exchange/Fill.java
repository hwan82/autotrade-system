package com.autotrade.sim.exchange;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.cost.FeeBreakdown;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 가상 체결 1건(virtual_trade_log 한 행의 체결 부분). 부분 체결은 {@code fillSeq}로 나뉜다.
 *
 * @param price 체결가(호가 단위에 맞음). 고정 슬리피지를 쓴 체결이면 반영 후 가격
 * @param slippage 기록용 불리한 가격 차이 × 수량. 이미 {@code price}에 들어 있으므로 다시 빼지 않는다.
 *     호가 기반 체결은 최우선 호가 대비 호가를 더 먹은 만큼, 체결가 기반 체결은 고정 슬리피지만큼
 * @param signalEventSeq 주문을 만든 신호의 시세 이벤트 순번(T)
 * @param fillEventSeq 체결가를 정한 시세 이벤트 순번. 항상 {@code signalEventSeq}보다 크다(미래 정보 금지)
 * @param exchangeDate 체결 이벤트의 거래소 현지 날짜(비용 표 구간 선택 기준)
 */
public record Fill(
        IdempotencyKey key,
        int fillSeq,
        Symbol symbol,
        Side side,
        OrderIntent intent,
        long quantity,
        BigDecimal price,
        FeeBreakdown fees,
        Money slippage,
        long signalEventSeq,
        long fillEventSeq,
        Instant signalTime,
        Instant fillTime,
        LocalDate exchangeDate) {

    public Fill {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(fees, "fees");
        Objects.requireNonNull(slippage, "slippage");
        Objects.requireNonNull(signalTime, "signalTime");
        Objects.requireNonNull(fillTime, "fillTime");
        Objects.requireNonNull(exchangeDate, "exchangeDate");
        if (fillSeq < 1 || quantity <= 0 || price.signum() <= 0) {
            throw new IllegalArgumentException("체결 값이 올바르지 않습니다: " + key + " #" + fillSeq);
        }
        if (fillEventSeq <= signalEventSeq) {
            throw new IllegalArgumentException("체결 이벤트가 신호 이벤트보다 앞설 수 없습니다(미래 정보): " + key);
        }
        price = price.stripTrailingZeros();
    }

    /** 체결 금액(가격 × 수량), 비용 제외. */
    public Money notional() {
        return new Money(price.multiply(BigDecimal.valueOf(quantity)), fees.commission().currency());
    }
}
