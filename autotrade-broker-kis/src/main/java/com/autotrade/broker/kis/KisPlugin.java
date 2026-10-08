package com.autotrade.broker.kis;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.broker.BrokerStatuses;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.LiveMarketDataSource;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.marketdata.live.BackoffPolicy;
import com.autotrade.marketdata.live.LiveRuntime;
import com.autotrade.marketdata.live.StreamingMarketDataSource;
import com.autotrade.marketdata.live.TokenCache;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 한국투자 플러그인(2단계: 국내 실시간 시세만). 주문·잔고 능력은 7단계 risk-ops 작업에서 더한다.
 * 키가 없으면 소스를 만들지 않고 NOT_CONFIGURED로 남는다(오류·알림 없음).
 */
public final class KisPlugin implements BrokerPlugin {

    public static final String SOURCE_ID = "KIS-WS";
    /** 무응답 판단 시간. 한국투자 PINGPONG 주기는 공개되지 않아 넉넉히 둔다 `[가정]`. */
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);

    private final boolean configured;
    private final LiveMarketDataSource source;

    private KisPlugin(boolean configured, LiveMarketDataSource source) {
        this.configured = configured;
        this.source = source;
    }

    public static KisPlugin create(BrokerCredentials credentials, LiveRuntime runtime) {
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(runtime, "runtime");
        if (!credentials.isConfigured()) {
            return new KisPlugin(false, null);
        }
        // 접속키 재발급은 만료 1시간 전. 토큰 재발급 1분당 1회 제한(H4)에 걸리지 않게 필요할 때만 받는다
        TokenCache approvals = new TokenCache(new KisApprovalClient(runtime.http(), credentials, runtime.clock()),
                runtime.clock(), Duration.ofHours(1));
        StreamingMarketDataSource src = new StreamingMarketDataSource(SOURCE_ID, new KisStreamProtocol(approvals),
                runtime.ws(), runtime.clock(), runtime.scheduler(), runtime.dispatchers().apply(SOURCE_ID),
                BackoffPolicy.standard(), IDLE_TIMEOUT);
        return new KisPlugin(true, src);
    }

    @Override
    public BrokerId brokerId() {
        return BrokerId.KIS;
    }

    @Override
    public BrokerStatus status() {
        return BrokerStatuses.fromConnection(configured, configured ? source.state() : ConnectionState.NEW);
    }

    @Override
    public Set<BrokerCapability> capabilities() {
        return configured ? EnumSet.of(BrokerCapability.REALTIME_QUOTE) : EnumSet.noneOf(BrokerCapability.class);
    }

    @Override
    public Set<Market> supportedMarkets() {
        return EnumSet.of(Market.KRX);
    }

    @Override
    public Optional<MarketDataSource> marketData(Market market) {
        return configured && market == Market.KRX ? Optional.of(source) : Optional.empty();
    }

    /** 실시간 소스(연결 수명 관리용). 키가 없으면 빈 값. */
    public Optional<LiveMarketDataSource> liveSource() {
        return Optional.ofNullable(source);
    }

    /** 2단계는 주문 출구가 없다(MD-01). */
    @Override
    public Optional<OrderGateway> orderGateway(AccountRef account) {
        return Optional.empty();
    }
}
