package com.autotrade.domain.order;

import com.autotrade.domain.market.Symbol;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 주문 멱등 키 {@code {date}-{code}-{side}-{seq}} (프로젝트 지침 4장 2항).
 *
 * <ul>
 *   <li>{@code date}: 거래소 현지 날짜 yyyyMMdd ({@link com.autotrade.domain.MarketRules#tradingDate})</li>
 *   <li>{@code code}: 종목 코드('-' 불가, {@link Symbol} 참고)</li>
 *   <li>{@code side}: BUY 또는 SELL</li>
 *   <li>{@code seq}: 같은 날·종목·방향 안에서 1부터 증가하는 번호</li>
 * </ul>
 * 같은 키는 한 계좌에서 한 번만 전송될 수 있다. 새 키는 계좌별 영속 발급기만 만든다(seq를 직접 올려 만들지 않는다).
 * 발급기와 "원키당 재발주 1회" 제한은 주문 경로(risk-ops 별도 작업)에서 구현한다.
 */
public record IdempotencyKey(LocalDate date, String code, Side side, int seq) {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    public IdempotencyKey {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(side, "side");
        if (code.contains("-") || code.isBlank()) {
            throw new IllegalArgumentException("멱등 키 종목 코드 형식이 아닙니다: " + code);
        }
        if (seq < 1) {
            throw new IllegalArgumentException("seq는 1 이상이어야 합니다: " + seq);
        }
    }

    public static IdempotencyKey of(LocalDate date, Symbol symbol, Side side, int seq) {
        return new IdempotencyKey(date, symbol.code(), side, seq);
    }

    public static IdempotencyKey parse(String text) {
        Objects.requireNonNull(text, "text");
        String[] parts = text.split("-", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("멱등 키 형식이 아닙니다: " + text);
        }
        try {
            return new IdempotencyKey(
                    LocalDate.parse(parts[0], BASIC), parts[1], Side.valueOf(parts[2]), Integer.parseInt(parts[3]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("멱등 키 형식이 아닙니다: " + text, e);
        }
    }


    @Override
    public String toString() {
        return BASIC.format(date) + "-" + code + "-" + side + "-" + seq;
    }
}
