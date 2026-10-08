package com.autotrade.risk.guard;

import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class GuardFixtures {

    static final AccountId KIS_KR = new AccountId("KIS-KR-1");
    static final AccountId KIWOOM_KR = new AccountId("KIWOOM-KR-1");
    static final List<AccountRef> ACCOUNTS = List.of(
            new AccountRef(KIS_KR, BrokerId.KIS, Market.KRX),
            new AccountRef(KIWOOM_KR, BrokerId.KIWOOM, Market.KRX));
    static final Symbol SAMSUNG = Symbol.krx("005930");

    private GuardFixtures() {}

    static OrderRequest order(AccountId account, OrderIntent intent) {
        Side side = intent == OrderIntent.ENTRY ? Side.BUY : Side.SELL;
        return new OrderRequest(IdempotencyKey.of(LocalDate.of(2026, 10, 8), SAMSUNG, side, 1),
                account, SAMSUNG, side, intent, OrderType.MARKET, null, 10);
    }

    /** 테스트에서 앞으로 돌릴 수 있는 시계. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class RecordingAudit implements GuardAuditLog {
        final List<GuardTransition> records = new ArrayList<>();
        boolean failing;

        @Override
        public void record(GuardTransition transition) {
            if (failing) {
                throw new IllegalStateException("감사 저장소 장애");
            }
            records.add(transition);
        }
    }
}
