package com.autotrade.marketdata.daily;

import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.rules.InstrumentKind;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * 종목 이력 한 구간(MD-07, Q-012). 같은 종목이 소속부를 옮기면(코스닥 → 코스피) 구간이 둘이 된다.
 * 상장폐지 종목은 마지막 구간의 {@code validTo}가 폐지일이다. 이 표로 "그날 상장돼 있던 종목"을 재현한다.
 *
 * @param board 소속부·거래소(KOSPI, KOSDAQ, KONEX, NYSE, NASDAQ 등)
 * @param validTo 마지막 거래일(포함). 진행 중이면 빈 값
 */
public record InstrumentPeriod(
        Symbol symbol, String name, InstrumentKind kind, String board, LocalDate validFrom, Optional<LocalDate> validTo) {

    public InstrumentPeriod {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validTo, "validTo");
        if (validTo.isPresent() && validTo.get().isBefore(validFrom)) {
            throw new IllegalArgumentException(symbol + " 이력 구간이 뒤집혔습니다: " + validFrom + " ~ " + validTo.get());
        }
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(validFrom) && validTo.map(to -> !date.isAfter(to)).orElse(true);
    }
}
