package com.autotrade.archfixture;

import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.RiskPolicy;

/** ArchitectureTest가 리스크 모듈 밖의 RiskPolicy 구현을 잡아내는지 확인하는 일부러 잘못된 코드. 운영 코드에서 참조하지 않는다. */
public final class ApproveAllRiskPolicy implements RiskPolicy {

    @Override
    public RiskDecision evaluate(OrderRequest order, AccountSnapshot account) {
        return RiskDecision.approve();
    }
}
