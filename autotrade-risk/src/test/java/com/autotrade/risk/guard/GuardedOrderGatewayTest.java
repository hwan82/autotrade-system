package com.autotrade.risk.guard;

import static com.autotrade.risk.guard.GuardFixtures.ACCOUNTS;
import static com.autotrade.risk.guard.GuardFixtures.KIS_KR;
import static com.autotrade.risk.guard.GuardFixtures.KIWOOM_KR;
import static com.autotrade.risk.guard.GuardFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderAck;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderQueryResult;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.risk.guard.GuardFixtures.MutableClock;
import com.autotrade.risk.guard.GuardFixtures.RecordingAudit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 실전 주문 출구가 Guard를 우회하지 못하는지(RSK-01, 안전 불변식 1·2). */
class GuardedOrderGatewayTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T00:00:00Z"));
    private final RealTradingGuard guard = new RealTradingGuard(ACCOUNTS, clock, new RecordingAudit());
    private final SecondConfirmation.Issuer issuer = guard.confirmationIssuer();
    private final FakeBroker broker = new FakeBroker(KIS_KR);

    private void open(AccountId account) {
        clock.advance(Duration.ofSeconds(1));
        guard.beginActivation(issuer.issue(account, "BAE", clock.instant()));
        for (ActivationStep step : ActivationStep.values()) {
            guard.completeStep(account, step);
        }
    }

    @Test
    void OFF_계좌로는_출구를_만들_수_없다() {
        assertThatThrownBy(() -> new GuardedOrderGateway(guard, guard.issueSessionPermit(KIS_KR), broker))
                .isInstanceOf(GuardClosedException.class);
    }

    @Test
    void OPEN이면_증권사로_전달한다() {
        open(KIS_KR);
        GuardedOrderGateway gw = new GuardedOrderGateway(guard, guard.issueSessionPermit(KIS_KR), broker);

        OrderAck ack = gw.submit(order(KIS_KR, OrderIntent.ENTRY));

        assertThat(ack.state()).isEqualTo(OrderState.ACCEPTED);
        assertThat(broker.submitted).hasSize(1);
    }

    @Test
    void Guard가_OFF로_내려가면_같은_출구로_더는_주문이_나가지_않는다() {
        open(KIS_KR);
        GuardedOrderGateway gw = new GuardedOrderGateway(guard, guard.issueSessionPermit(KIS_KR), broker);

        guard.shutdown(KIS_KR, "BAE", "킬스위치");
        OrderAck ack = gw.submit(order(KIS_KR, OrderIntent.STOP_LOSS));

        assertThat(ack.state()).isEqualTo(OrderState.REJECTED);
        assertThat(ack.duplicateKey()).isFalse();
        assertThat(ack.message()).startsWith(RealTradingGuard.RULE_ID);
        assertThat(broker.submitted).isEmpty();
        assertThat(gw.query(order(KIS_KR, OrderIntent.STOP_LOSS).key()))
                .isInstanceOf(OrderQueryResult.Indeterminate.class);
        assertThat(gw.cancel(order(KIS_KR, OrderIntent.STOP_LOSS).key()).state()).isEqualTo(OrderState.UNKNOWN);
        assertThat(broker.calls).isZero();
    }

    @Test
    void 다시_열어도_예전_출구는_살아나지_않는다() {
        open(KIS_KR);
        GuardedOrderGateway old = new GuardedOrderGateway(guard, guard.issueSessionPermit(KIS_KR), broker);
        guard.shutdown(KIS_KR, "BAE", "킬스위치");
        open(KIS_KR);

        assertThat(old.submit(order(KIS_KR, OrderIntent.ENTRY)).state()).isEqualTo(OrderState.REJECTED);
        assertThat(broker.submitted).isEmpty();
    }

    @Test
    void 보호_모드에서는_신규_매수를_막고_손절_매도와_취소는_보낸다() {
        open(KIS_KR);
        GuardedOrderGateway gw = new GuardedOrderGateway(guard, guard.issueSessionPermit(KIS_KR), broker);
        guard.deactivate(KIS_KR, "BAE", "사용자 OFF");

        OrderAck entry = gw.submit(order(KIS_KR, OrderIntent.ENTRY));
        OrderAck stop = gw.submit(order(KIS_KR, OrderIntent.STOP_LOSS));
        gw.cancel(order(KIS_KR, OrderIntent.ENTRY).key());

        assertThat(entry.state()).isEqualTo(OrderState.REJECTED);
        assertThat(stop.state()).isEqualTo(OrderState.ACCEPTED);
        assertThat(broker.submitted).extracting(OrderRequest::intent).containsExactly(OrderIntent.STOP_LOSS);
        assertThat(broker.cancelled).hasSize(1);
    }

    @Test
    void 다른_계좌의_출구에_허가를_붙일_수_없다() {
        open(KIWOOM_KR);
        OrderSessionPermit kiwoomPermit = guard.issueSessionPermit(KIWOOM_KR);

        assertThatThrownBy(() -> new GuardedOrderGateway(guard, kiwoomPermit, broker))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다른_Guard가_발급한_허가는_쓸_수_없다() {
        RealTradingGuard other = new RealTradingGuard(ACCOUNTS, clock, new RecordingAudit());
        clock.advance(Duration.ofSeconds(1));
        other.beginActivation(other.confirmationIssuer().issue(KIS_KR, "BAE", clock.instant()));
        for (ActivationStep step : ActivationStep.values()) {
            other.completeStep(KIS_KR, step);
        }

        assertThatThrownBy(() -> new GuardedOrderGateway(guard, other.issueSessionPermit(KIS_KR), broker))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다른_계좌_주문을_이_출구로_보낼_수_없다() {
        open(KIS_KR);
        open(KIWOOM_KR);
        GuardedOrderGateway gw = new GuardedOrderGateway(guard, guard.issueSessionPermit(KIS_KR), broker);

        assertThatThrownBy(() -> gw.submit(order(KIWOOM_KR, OrderIntent.ENTRY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(broker.submitted).isEmpty();
    }

    /** 증권사 출구 대역. 호출만 기록한다. */
    static final class FakeBroker implements OrderGateway {
        final AccountId account;
        final List<OrderRequest> submitted = new ArrayList<>();
        final List<IdempotencyKey> cancelled = new ArrayList<>();
        int calls;

        FakeBroker(AccountId account) {
            this.account = account;
        }

        @Override
        public AccountId account() {
            return account;
        }

        @Override
        public OrderAck submit(OrderRequest request) {
            calls++;
            submitted.add(request);
            return new OrderAck(request.key(), OrderState.ACCEPTED, false, "B-1", "");
        }

        @Override
        public OrderQueryResult query(IdempotencyKey key) {
            calls++;
            return OrderQueryResult.NotFound.INSTANCE;
        }

        @Override
        public OrderAck cancel(IdempotencyKey key) {
            calls++;
            cancelled.add(key);
            return new OrderAck(key, OrderState.CANCEL_REQUESTED, false, "B-1", "");
        }
    }
}
