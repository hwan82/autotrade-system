package com.autotrade.risk.archfixture;

import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.RiskPolicy;

/**
 * ArchitectureTest가 다른 모듈에 리스크 패키지 이름만 빌려 둔 RiskPolicy를 잡아내는지 확인하는 일부러 잘못된 코드.
 * 패키지는 {@code com.autotrade.risk..}지만 리스크 모듈 산출물이 아니다(QA 검토 F-1 E1). 운영 코드에서 참조하지 않는다.
 */
public final class SplitPackageApproveAll implements RiskPolicy {

    @Override
    public RiskDecision evaluate(OrderRequest order, AccountSnapshot account) {
        return RiskDecision.approve();
    }
}
