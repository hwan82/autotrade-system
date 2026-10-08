package com.autotrade.broker.toss;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.marketdata.live.HttpPort;
import com.autotrade.marketdata.polling.PollingQuoteSource;
import com.autotrade.marketdata.live.TokenCache;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 토스 플러그인(2단계: 현재가 폴링만, D-008). 실시간 소스 목록에 들어가지 않는다(REALTIME_QUOTE 없음).
 *
 * <p>D-008이 바뀌어 토스 WebSocket을 실시간 소스로 쓰게 되면(market-data-limits.md 4장 A안), 이 플러그인에
 * {@code StreamProtocol} 구현 하나와 REALTIME_QUOTE 능력을 더하면 된다. 게이트웨이의 소스 선택은 능력과
 * {@code market-data.priority}만 보므로 다른 모듈은 고치지 않는다(BRK-06).
 *
 * <p>상태: 키가 있으면 CONFIGURED. 폴링은 호출 단위라 연결 상태가 없어 ACTIVE·DEGRADED 판단은 부르는 쪽(보호 감시)이 한다.
 */
public final class TossPlugin implements BrokerPlugin {

    private final boolean configured;
    private final TossPricePoller poller;

    private TossPlugin(boolean configured, TossPricePoller poller) {
        this.configured = configured;
        this.poller = poller;
    }

    public static TossPlugin create(BrokerCredentials credentials, HttpPort http, Clock clock) {
        Objects.requireNonNull(credentials, "credentials");
        if (!credentials.isConfigured()) {
            return new TossPlugin(false, null);
        }
        TokenCache tokens = new TokenCache(new TossTokenClient(http, credentials, clock), clock, Duration.ofMinutes(10));
        return new TossPlugin(true, new TossPricePoller(http, tokens, clock));
    }

    @Override
    public BrokerId brokerId() {
        return BrokerId.TOSS;
    }

    @Override
    public BrokerStatus status() {
        return configured ? BrokerStatus.CONFIGURED : BrokerStatus.NOT_CONFIGURED;
    }

    @Override
    public Set<BrokerCapability> capabilities() {
        return configured ? EnumSet.of(BrokerCapability.POLLING_QUOTE) : EnumSet.noneOf(BrokerCapability.class);
    }

    @Override
    public Set<Market> supportedMarkets() {
        return EnumSet.of(Market.KRX, Market.US);
    }

    @Override
    public Optional<MarketDataSource> marketData(Market market) {
        return Optional.empty();
    }

    @Override
    public Optional<PollingQuoteSource> pollingQuotes() {
        return Optional.ofNullable(poller);
    }

    @Override
    public Optional<OrderGateway> orderGateway(AccountRef account) {
        return Optional.empty();
    }
}
