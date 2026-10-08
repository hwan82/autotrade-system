package com.autotrade.broker;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.marketdata.MarketDataSource;
import java.util.Optional;
import java.util.Set;

/**
 * 증권사 플러그인 공통 계약. 플러그인(kis, kiwoom, toss)은 서로 참조하지 않는다.
 *
 * <p>계약
 * <ul>
 *   <li>{@link #capabilities()}에 없는 기능은 빈 값을 돌려준다. 엔진은 상태가 {@link BrokerStatus#ACTIVE}이고
 *       능력이 선언된 경우에만 기능을 쓴다.</li>
 *   <li>{@link #orderGateway}는 이 증권사의 계좌에만 주문 출구를 준다. 다른 증권사 계좌를 대신 처리하지 않는다(D-010).
 *       실전 주문 출구는 RealTradingGuard를 통과해야 하며, 그 연결은 risk-ops 작업에서 정의한다.</li>
 *   <li>{@link #marketData}가 돌려주는 소스는 주문 API를 호출하지 않는다.</li>
 *   <li>상태 조회는 스레드 안전하고 블로킹하지 않는다.</li>
 * </ul>
 */
public interface BrokerPlugin {

    BrokerId brokerId();

    BrokerStatus status();

    Set<BrokerCapability> capabilities();

    Set<Market> supportedMarkets();

    default boolean supports(BrokerCapability capability) {
        return status().isUsable() && capabilities().contains(capability);
    }

    Optional<MarketDataSource> marketData(Market market);

    Optional<OrderGateway> orderGateway(AccountId account);
}
