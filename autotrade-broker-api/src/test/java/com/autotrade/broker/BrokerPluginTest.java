package com.autotrade.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.marketdata.MarketDataSource;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerPluginTest {

    @Test
    void ACTIVE가_아니면_선언한_능력도_쓰지_않는다() {
        for (BrokerStatus status : BrokerStatus.values()) {
            BrokerPlugin plugin = fake(status, EnumSet.of(BrokerCapability.ORDER));
            assertThat(plugin.supports(BrokerCapability.ORDER)).isEqualTo(status == BrokerStatus.ACTIVE);
        }
    }

    @Test
    void 선언하지_않은_능력은_ACTIVE여도_쓰지_않는다() {
        BrokerPlugin toss = fake(BrokerStatus.ACTIVE, EnumSet.of(BrokerCapability.POLLING_QUOTE));
        assertThat(toss.supports(BrokerCapability.REALTIME_QUOTE)).isFalse();
    }

    private static BrokerPlugin fake(BrokerStatus status, Set<BrokerCapability> caps) {
        return new BrokerPlugin() {
            @Override
            public BrokerId brokerId() {
                return BrokerId.TOSS;
            }

            @Override
            public BrokerStatus status() {
                return status;
            }

            @Override
            public Set<BrokerCapability> capabilities() {
                return caps;
            }

            @Override
            public Set<Market> supportedMarkets() {
                return EnumSet.of(Market.KRX);
            }

            @Override
            public Optional<MarketDataSource> marketData(Market market) {
                return Optional.empty();
            }

            @Override
            public Optional<OrderGateway> orderGateway(AccountId account) {
                return Optional.empty();
            }
        };
    }
}
