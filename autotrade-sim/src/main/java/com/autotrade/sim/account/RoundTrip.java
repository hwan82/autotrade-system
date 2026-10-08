package com.autotrade.sim.account;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 왕복 거래 1건: 한 종목 보유가 0에서 시작해 다시 0이 될 때까지(추가 매수·분할 매도 포함).
 * 승률·손익비·평균 보유일의 단위다.
 *
 * @param pnl 비용 차감 후 실현 손익(매수·매도 비용 모두 포함)
 */
public record RoundTrip(String lotId, Symbol symbol, LocalDate entryDate, LocalDate exitDate, Money pnl) {

    public RoundTrip {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(entryDate, "entryDate");
        Objects.requireNonNull(exitDate, "exitDate");
        Objects.requireNonNull(pnl, "pnl");
    }
}
