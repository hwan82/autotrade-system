package com.autotrade.marketdata.daily;

import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 일봉 한 개(MD-07). 가격은 실제 체결 기준 원가격이고, 수정주가는 {@code adjFactor}를 곱해 얻는다.
 * 신호는 수정주가로, 체결은 실제 가격으로 계산한다(MD-10).
 *
 * <p>값 검사(0원, 고저 모순 등)는 생성자가 아니라 {@link DailyBarQualityCheck}가 한다. 잘못된 행도 리포트에 남겨야 하기 때문이다.
 * {@link SnapshotDailyBarSource}는 오류 행을 빼고 내준다.
 *
 * @param adjFactor 스냅샷 시점 기준 누적 수정 계수(분할·병합·배당락). 조정이 없으면 1
 */
public record DailyBar(
        Symbol symbol,
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal adjFactor) {

    public DailyBar {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(adjFactor, "adjFactor");
        open = open.stripTrailingZeros();
        high = high.stripTrailingZeros();
        low = low.stripTrailingZeros();
        close = close.stripTrailingZeros();
        adjFactor = adjFactor.stripTrailingZeros();
    }

    /** 수정 종가. 소수 자릿수가 길어질 수 있어 유효숫자 16자리로 자른다(신호 계산용, 체결가로 쓰지 않는다). */
    public BigDecimal adjustedClose() {
        return close.multiply(adjFactor, MathContext.DECIMAL64);
    }
}
