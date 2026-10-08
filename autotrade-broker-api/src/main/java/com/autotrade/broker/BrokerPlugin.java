package com.autotrade.broker;

import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.marketdata.polling.PollingQuoteSource;
import java.util.Optional;
import java.util.Set;

/**
 * 증권사 플러그인 공통 계약. 플러그인(kis, kiwoom, toss)은 서로 참조하지 않는다.
 *
 * <p>계약
 * <ul>
 *   <li>{@link #capabilities()}에 없는 기능은 빈 값을 돌려준다.</li>
 *   <li>상태별로 어떤 주문을 허용할지(예: DEGRADED에서 신규 진입은 막고 청산·손절·서버 측 손절은 유지)는
 *       플러그인이 아니라 엔진의 리스크·장애 대응 정책이 {@code OrderIntent}로 판단한다(risk-ops 작업).</li>
 *   <li>{@link #orderGateway}는 {@code account.broker()}가 {@link #brokerId()}와 같을 때만 출구를 준다.
 *       다르면 빈 값이다. 다른 증권사 계좌를 대신 처리하지 않는다(D-010).</li>
 *   <li>엔진은 실전 주문 출구를 이 메서드에서 직접 받지 않고 RealTradingGuard를 거친 출구만 쓴다.
 *       그 연결은 risk-ops 작업에서 정의한다.</li>
 *   <li>{@link #marketData}와 {@link #pollingQuotes}가 돌려주는 소스는 주문 API를 호출하지 않는다.</li>
 *   <li>상태 조회는 스레드 안전하고 블로킹하지 않는다.</li>
 * </ul>
 */
public interface BrokerPlugin {

    BrokerId brokerId();

    BrokerStatus status();

    Set<BrokerCapability> capabilities();

    Set<Market> supportedMarkets();

    Optional<MarketDataSource> marketData(Market market);

    /** 폴링 현재가(능력 POLLING_QUOTE, D-008 보유 종목 보호용). 없으면 빈 값(ADR-002 2.1). */
    default Optional<PollingQuoteSource> pollingQuotes() {
        return Optional.empty();
    }

    Optional<OrderGateway> orderGateway(AccountRef account);
}
