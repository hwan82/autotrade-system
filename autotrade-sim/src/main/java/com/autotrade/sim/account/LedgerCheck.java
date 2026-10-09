package com.autotrade.sim.account;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.exchange.Fill;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 가상 계좌 불변식(SIM-15, 실전 5분 대사에 해당). 원장만으로 현금과 보유 수량을 다시 계산해 계좌 상태와 맞춘다.
 * <ul>
 *   <li>현금 = 시드 − Σ(매수 금액 + 매수 비용) + Σ(매도 금액 − 매도 비용)</li>
 *   <li>종목별 보유 수량 = Σ 매수 수량 − Σ 매도 수량, 음수 없음</li>
 *   <li>현금 ≥ 0</li>
 * </ul>
 */
public final class LedgerCheck {

    private LedgerCheck() {
    }

    public static void verify(Money seed, List<AppliedFill> ledger, Money cash, SortedMap<Symbol, Long> quantities) {
        Objects.requireNonNull(seed, "seed");
        Money expectedCash = seed;
        TreeMap<Symbol, Long> expectedQty = new TreeMap<>();
        for (AppliedFill a : ledger) {
            Fill f = a.fill();
            Money flow = f.notional();
            if (f.side() == Side.BUY) {
                expectedCash = expectedCash.minus(flow.plus(f.fees().total()));
                expectedQty.merge(f.symbol(), f.quantity(), Long::sum);
            } else {
                expectedCash = expectedCash.plus(flow.minus(f.fees().total()));
                expectedQty.merge(f.symbol(), -f.quantity(), Long::sum);
            }
        }
        expectedQty.values().removeIf(q -> q == 0L);
        for (Map.Entry<Symbol, Long> e : expectedQty.entrySet()) {
            if (e.getValue() < 0) {
                throw new IllegalStateException("SIM-15 불변식 위반: 보유 수량이 음수입니다: " + e.getKey());
            }
        }
        if (!expectedCash.equals(cash)) {
            throw new IllegalStateException("SIM-15 불변식 위반: 현금 " + cash + " ≠ 원장 합계 " + expectedCash);
        }
        if (!expectedQty.equals(new TreeMap<>(quantities))) {
            throw new IllegalStateException("SIM-15 불변식 위반: 보유 수량 " + quantities + " ≠ 원장 합계 " + expectedQty);
        }
        if (cash.signum() < 0) {
            throw new IllegalStateException("SIM-15 불변식 위반: 현금이 음수입니다: " + cash);
        }
    }
}
