package qa.probe;

import com.autotrade.broker.BrokerPlugin;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.order.OrderGateway;
import java.util.Optional;

/**
 * 위반 픽스처(독립 QA PR #21 E3 형태): {@code com.autotrade} 밖 패키지에서 Guard 팩토리 없이 증권사 주문 출구를 연다.
 */
public final class OutsideOpener {

    private OutsideOpener() {}

    public static Optional<OrderGateway> open(BrokerPlugin plugin, AccountRef account) {
        return plugin.orderGateway(account);
    }
}
