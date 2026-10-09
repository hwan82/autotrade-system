package com.autotrade.app.engine.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderAck;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderQueryResult;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.risk.guard.ActivationStep;
import com.autotrade.risk.guard.GuardClosedException;
import com.autotrade.risk.guard.GuardedOrderGateway;
import com.autotrade.risk.guard.RealTradingGuard;
import com.autotrade.risk.guard.SecondConfirmation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 실전 주문 출구는 Guard 허가 뒤에만, 계좌의 증권사로만 만들어진다(RSK-01, D-010). */
class RealOrderGatewaysTest {

    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    private static final AccountRef KIS_KR = new AccountRef(new AccountId("KIS-KR-1"), BrokerId.KIS, Market.KRX);
    private static final Symbol SAMSUNG = Symbol.krx("005930");

    private final RealTradingGuard guard =
            new RealTradingGuard(List.of(KIS_KR), Clock.fixed(T0, ZoneOffset.UTC), t -> { });
    private final FakePlugin kis = new FakePlugin(BrokerId.KIS);

    static OrderRequest order(AccountId account, OrderIntent intent) {
        Side side = intent == OrderIntent.ENTRY ? Side.BUY : Side.SELL;
        return new OrderRequest(IdempotencyKey.of(LocalDate.of(2026, 10, 8), SAMSUNG, side, 1),
                account, SAMSUNG, side, intent, OrderType.MARKET, null, 1);
    }

    private void open() {
        guard.beginActivation(new SecondConfirmation(KIS_KR.id(), "BAE", T0));
        for (ActivationStep step : ActivationStep.values()) {
            guard.completeStep(KIS_KR.id(), step);
        }
    }

    @Test
    void OFF_계좌는_플러그인_주문_출구를_만들기_전에_막힌다() {
        assertThatThrownBy(() -> RealOrderGateways.open(guard, kis, KIS_KR)).isInstanceOf(GuardClosedException.class);
        assertThat(kis.orderGatewayCalls).isZero();
    }

    @Test
    void 열린_계좌는_Guard를_거친_출구를_받는다() {
        open();

        OrderGateway gw = RealOrderGateways.open(guard, kis, KIS_KR);

        assertThat(gw).isInstanceOf(GuardedOrderGateway.class);
        assertThat(gw.submit(order(KIS_KR.id(), OrderIntent.ENTRY)).state()).isEqualTo(OrderState.ACCEPTED);
        guard.shutdown(KIS_KR.id(), "BAE", "킬스위치");
        assertThat(gw.submit(order(KIS_KR.id(), OrderIntent.STOP_LOSS)).state()).isEqualTo(OrderState.REJECTED);
        assertThat(kis.submitted).hasSize(1);
    }

    @Test
    void 다른_증권사_플러그인으로는_출구를_만들_수_없다() {
        open();
        FakePlugin kiwoom = new FakePlugin(BrokerId.KIWOOM);

        assertThatThrownBy(() -> RealOrderGateways.open(guard, kiwoom, KIS_KR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("D-010");
        assertThat(kiwoom.orderGatewayCalls).isZero();
    }

    @Test
    void Guard에_없는_계좌는_거부한다() {
        AccountRef unknown = new AccountRef(new AccountId("KIS-US-9"), BrokerId.KIS, Market.US);
        assertThatThrownBy(() -> RealOrderGateways.open(guard, kis, unknown))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(kis.orderGatewayCalls).isZero();
    }

    static final class FakePlugin implements BrokerPlugin {
        final BrokerId id;
        int orderGatewayCalls;
        final List<OrderRequest> submitted = new ArrayList<>();

        FakePlugin(BrokerId id) {
            this.id = id;
        }

        @Override
        public BrokerId brokerId() {
            return id;
        }

        @Override
        public BrokerStatus status() {
            return BrokerStatus.ACTIVE;
        }

        @Override
        public Set<BrokerCapability> capabilities() {
            return Set.of(BrokerCapability.ORDER);
        }

        @Override
        public Set<Market> supportedMarkets() {
            return Set.of(Market.KRX);
        }

        @Override
        public Optional<MarketDataSource> marketData(Market market) {
            return Optional.empty();
        }

        @Override
        public Optional<OrderGateway> orderGateway(AccountRef account) {
            orderGatewayCalls++;
            if (account.broker() != id) {
                return Optional.empty();
            }
            return Optional.of(new OrderGateway() {
                @Override
                public AccountId account() {
                    return account.id();
                }

                @Override
                public OrderAck submit(OrderRequest request) {
                    submitted.add(request);
                    return new OrderAck(request.key(), OrderState.ACCEPTED, false, "B-1", "");
                }

                @Override
                public OrderQueryResult query(IdempotencyKey key) {
                    return OrderQueryResult.NotFound.INSTANCE;
                }

                @Override
                public OrderAck cancel(IdempotencyKey key) {
                    return new OrderAck(key, OrderState.CANCEL_REQUESTED, false, "B-1", "");
                }
            });
        }
    }
}
