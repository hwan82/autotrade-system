package com.autotrade.realstore;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class StoreFixtures {

    static final AccountId KIS = new AccountId("KIS-KR-1");
    static final AccountId KIWOOM = new AccountId("KIWOOM-KR-1");
    static final List<AccountRef> ACCOUNTS = List.of(
            new AccountRef(KIS, BrokerId.KIS, Market.KRX), new AccountRef(KIWOOM, BrokerId.KIWOOM, Market.KRX));
    /** 시험용 계좌번호(비밀값 자리). 실제 번호가 아니다. */
    static final String FAKE_ACCOUNT_NUMBER = "00000000-99";

    static final MarketRules KRX = new MarketRules() {
        @Override
        public Market market() {
            return Market.KRX;
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public boolean isRegularSession(Instant instant) {
            return true;
        }

        @Override
        public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
            return BigDecimal.ONE;
        }
    };

    private StoreFixtures() {}

    static EngineStore.Options options(Path configDir, Clock clock, RecordingAlerts alerts) {
        return options(configDir, clock, alerts, EngineStore.Limits.DEFAULT);
    }

    static EngineStore.Options options(Path configDir, Clock clock, RecordingAlerts alerts, EngineStore.Limits limits) {
        return new EngineStore.Options(configDir, ACCOUNTS, ref -> KRX, clock, 4242, () -> List.of(FAKE_ACCOUNT_NUMBER),
                alerts, limits);
    }

    static EngineStore.Limits fast() {
        return new EngineStore.Limits(Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofSeconds(2), 1_000, 1_000,
                Duration.ofHours(1));
    }

    static final class RecordingAlerts implements StoreAlerts {
        final List<String> titles = Collections.synchronizedList(new ArrayList<>());
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void urgent(String title, String body) {
            titles.add(title);
            bodies.add(body);
        }
    }

    /** 시험에서 앞으로 돌릴 수 있는 시계. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

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
}
