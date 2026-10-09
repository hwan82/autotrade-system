package com.autotrade.app.engine.order;

import com.autotrade.broker.BrokerPlugin;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.order.OrderGateway;
import java.util.Optional;

/**
 * {@code ModuleOwnershipTest} 검증용 위반 코드(운영 코드 아님): autotrade-app-engine이 아닌 모듈이 실전 출구 팩토리의 패키지
 * ({@code com.autotrade.app.engine.order})에 클래스를 두고 증권사 주문 출구를 직접 연다. 다른 모듈이 같은 FQN
 * ({@code RealOrderGateways})을 정의하는 경우도 같은 소유 규칙으로 막힌다. 같은 FQN을 이 테스트 소스에 두면 진짜 클래스를
 * 가리므로 이름만 다르게 했다.
 */
public final class SplitPackageGatewayOpener {

    private SplitPackageGatewayOpener() {}

    public static Optional<OrderGateway> open(BrokerPlugin plugin, AccountRef account) {
        return plugin.orderGateway(account);
    }
}
