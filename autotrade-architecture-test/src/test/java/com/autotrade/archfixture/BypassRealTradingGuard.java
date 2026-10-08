package com.autotrade.archfixture;

import com.autotrade.broker.BrokerPlugin;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.order.OrderGateway;
import java.util.Optional;

/** 규칙 검증용 위반 코드: Guard를 거치지 않고 증권사 주문 출구를 직접 얻는다. 운영 코드가 아니다. */
public final class BypassRealTradingGuard {

    private BypassRealTradingGuard() {}

    public static Optional<OrderGateway> rawGateway(BrokerPlugin plugin, AccountRef account) {
        return plugin.orderGateway(account);
    }
}
