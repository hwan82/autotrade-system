package com.autotrade.sim.account;

import com.autotrade.domain.Money;
import com.autotrade.sim.exchange.Fill;
import java.util.Objects;

/**
 * 계좌에 반영된 체결. 진입-청산 연결(lot)과 청산 실현 손익을 붙인다.
 *
 * @param realizedPnl 매도 체결의 비용 차감 후 실현 손익. 매수 체결은 null
 */
public record AppliedFill(Fill fill, String lotId, Money realizedPnl) {

    public AppliedFill {
        Objects.requireNonNull(fill, "fill");
        Objects.requireNonNull(lotId, "lotId");
    }
}
