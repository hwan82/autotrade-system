package com.autotrade.sim.testing;

import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.RiskPolicy;
import java.util.Set;

/** 테스트용 블랙리스트 정책. 실제 검증 체인(블랙리스트 → 리스크 → 멱등 키 → 상태머신)은 risk-ops 작업이다. */
public record TestBlacklist(Set<Symbol> symbols) implements RiskPolicy {

    @Override
    public RiskDecision evaluate(OrderRequest order, AccountSnapshot account) {
        return symbols.contains(order.symbol())
                ? RiskDecision.reject("BLACKLIST", "제외 종목: " + order.symbol())
                : RiskDecision.approve();
    }
}
