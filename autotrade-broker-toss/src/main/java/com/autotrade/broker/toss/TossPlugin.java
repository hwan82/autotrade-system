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
import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 토스 플러그인(D-053: 읽기 전용 조회와 현재가 폴링만). 키가 없으면 아무것도 만들지 않고 NOT_CONFIGURED로 남는다.
 *
 * <ul>
 *   <li>실시간(WebSocket, D-024 2순위)은 아직 없다. 토큰 소유(Q-042)를 정하고 {@code TossStreamProtocol}을 더할 때
 *       {@code REALTIME_QUOTE}를 선언한다. 그전까지 {@link #marketData}는 빈 값이다.</li>
 *   <li>주문·잔고 능력은 7단계 risk-ops 작업이다. {@link #orderGateway}는 항상 빈 값이다.</li>
 *   <li>어느 프로세스도 아직 이 플러그인을 띄우지 않는다. 토스 토큰은 클라이언트당 1개라 토큰을 가질 프로세스를 정한 뒤
 *       연결한다(ADR-003 4장, docs/research/toss-openapi-spec.md 7장).</li>
 * </ul>
 */
public final class TossPlugin implements BrokerPlugin {

    private final TossRestClient client;
    private final TossPollingQuoteSource polling;

    private TossPlugin(TossRestClient client, TossPollingQuoteSource polling) {
        this.client = client;
        this.polling = polling;
    }

    public static TossPlugin create(BrokerCredentials credentials, HttpPort http, Clock clock) {
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(http, "http");
        Objects.requireNonNull(clock, "clock");
        if (!credentials.isConfigured()) {
            return new TossPlugin(null, null);
        }
        TossRestClient client = TossRestClient.create(credentials, http, clock);
        return new TossPlugin(client, new TossPollingQuoteSource(client, clock));
    }

    /** 테스트용: 가짜 발급기·HTTP로 만든 호출기를 쓴다. */
    static TossPlugin of(TossRestClient client, Clock clock) {
        return new TossPlugin(client, new TossPollingQuoteSource(client, clock));
    }

    @Override
    public BrokerId brokerId() {
        return BrokerId.TOSS;
    }

    @Override
    public BrokerStatus status() {
        if (client == null) {
            return BrokerStatus.NOT_CONFIGURED;
        }
        return switch (client.health()) {
            case UNKNOWN -> BrokerStatus.CONFIGURED;
            case OK -> BrokerStatus.ACTIVE;
            case DEGRADED -> BrokerStatus.DEGRADED;
        };
    }

    @Override
    public Set<BrokerCapability> capabilities() {
        return client == null ? EnumSet.noneOf(BrokerCapability.class) : EnumSet.of(BrokerCapability.POLLING_QUOTE);
    }

    @Override
    public Set<Market> supportedMarkets() {
        return EnumSet.of(Market.KRX, Market.US);
    }

    /** 실시간 미구현(위 설명). */
    @Override
    public Optional<MarketDataSource> marketData(Market market) {
        return Optional.empty();
    }

    @Override
    public Optional<PollingQuoteSource> pollingQuotes() {
        return Optional.ofNullable(polling);
    }

    /** 읽기 전용 조회 호출기(시세·종목·시장 정보). 키가 없으면 빈 값. */
    public Optional<TossRestClient> restClient() {
        return Optional.ofNullable(client);
    }

    /** 주문 출구 없음(MD-01, D-053). */
    @Override
    public Optional<OrderGateway> orderGateway(AccountRef account) {
        return Optional.empty();
    }
}
