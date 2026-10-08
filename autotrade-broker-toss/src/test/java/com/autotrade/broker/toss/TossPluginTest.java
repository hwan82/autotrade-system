package com.autotrade.broker.toss;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerCredentials;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TossPluginTest {

    @Test
    void 폴링_능력만_선언하고_실시간_소스와_주문_출구는_없다() {
        TossPlugin p = TossPlugin.create(new BrokerCredentials("APP-KEY", "SECRET-KEY"), new FakeHttp(),
                Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC));
        assertThat(p.capabilities()).containsExactly(BrokerCapability.POLLING_QUOTE);
        assertThat(p.pollingQuotes()).isPresent();
        assertThat(p.marketData(Market.KRX)).isEmpty();
        assertThat(p.orderGateway(new AccountRef(new AccountId("TOSS-KRX-1"), BrokerId.TOSS, Market.KRX))).isEmpty();
    }
}
