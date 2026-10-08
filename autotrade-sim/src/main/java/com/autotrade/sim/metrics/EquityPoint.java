package com.autotrade.sim.metrics;

import com.autotrade.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 자산 곡선 한 점(equity_curve 한 행). 거래일 마감마다 하나.
 *
 * @param ts 그 거래일 마지막 이벤트의 거래소 시각
 * @param equity 비용 차감 후 평가금액(현금 + 보유 평가)
 */
public record EquityPoint(LocalDate date, Instant ts, Money equity, Money cash) {

    public EquityPoint {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(equity, "equity");
        Objects.requireNonNull(cash, "cash");
    }
}
