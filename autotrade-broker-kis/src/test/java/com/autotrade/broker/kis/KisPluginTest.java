package com.autotrade.broker.kis;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.live.LiveRuntime;
import com.autotrade.marketdata.live.SerialDispatcher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class KisPluginTest {

    private final LiveRuntime runtime = new LiveRuntime(
            (endpoint, h) -> new CompletableFuture<>(),
            new FakeHttp(),
            Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC),
            (delay, task) -> () -> {},
            id -> SerialDispatcher.direct());

    @Test
    void 시세_능력만_선언하고_주문_출구는_어느_계좌에도_없다() {
        KisPlugin p = KisPlugin.create(new BrokerCredentials("APP-KEY", "SECRET-KEY"), runtime);
        assertThat(p.capabilities()).containsExactly(BrokerCapability.REALTIME_QUOTE);
        assertThat(p.orderGateway(new AccountRef(new AccountId("KIS-KRX-1"), BrokerId.KIS, Market.KRX))).isEmpty();
        assertThat(p.status()).isEqualTo(BrokerStatus.CONFIGURED);
    }

    @Test
    void 키가_없으면_설정_안_됨이고_소스를_만들지_않는다() {
        KisPlugin p = KisPlugin.create(BrokerCredentials.none(), runtime);
        assertThat(p.status()).isEqualTo(BrokerStatus.NOT_CONFIGURED);
        assertThat(p.marketData(Market.KRX)).isEmpty();
    }
}
