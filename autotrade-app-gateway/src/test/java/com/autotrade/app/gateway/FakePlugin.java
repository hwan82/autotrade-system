package com.autotrade.app.gateway;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.broker.BrokerStatuses;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.LiveMarketDataSource;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.marketdata.MarketEventListener;
import com.autotrade.marketdata.SourceStateListener;
import com.autotrade.marketdata.Subscription;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/** 시험용 플러그인과 실시간 소스. 상태를 손으로 바꾸고 구독 내역을 본다. */
final class FakePlugin implements BrokerPlugin {

    final BrokerId id;
    final Source source;

    FakePlugin(BrokerId id, int capacity) {
        this.id = id;
        this.source = new Source(id.name() + "-FAKE", capacity);
    }

    @Override
    public BrokerId brokerId() {
        return id;
    }

    @Override
    public BrokerStatus status() {
        return BrokerStatuses.fromConnection(true, source.state);
    }

    @Override
    public Set<BrokerCapability> capabilities() {
        return EnumSet.of(BrokerCapability.REALTIME_QUOTE);
    }

    @Override
    public Set<Market> supportedMarkets() {
        return EnumSet.of(Market.KRX);
    }

    @Override
    public Optional<MarketDataSource> marketData(Market market) {
        return market == Market.KRX ? Optional.of(source) : Optional.empty();
    }

    @Override
    public Optional<OrderGateway> orderGateway(AccountRef account) {
        throw new AssertionError("게이트웨이는 주문 출구를 찾지 않는다");
    }

    static final class Source implements LiveMarketDataSource {
        private final String id;
        private final int capacity;
        private final List<SourceStateListener> listeners = new CopyOnWriteArrayList<>();
        private final List<Sub> subs = new CopyOnWriteArrayList<>();
        volatile ConnectionState state = ConnectionState.NEW;
        volatile java.time.Instant lastReceived;
        boolean started;
        boolean closed;
        int maxUnion;

        Source(String id, int capacity) {
            this.id = id;
            this.capacity = capacity;
        }

        @Override
        public String sourceId() {
            return id;
        }

        @Override
        public int maxSubscriptions() {
            return capacity;
        }

        @Override
        public synchronized Subscription subscribe(Set<Symbol> symbols, MarketEventListener listener) {
            SortedSet<Symbol> union = registered();
            union.addAll(symbols);
            if (union.size() > capacity) {
                throw new IllegalArgumentException("한도 초과 " + union.size());
            }
            maxUnion = Math.max(maxUnion, union.size());
            Sub s = new Sub(Collections.unmodifiableSortedSet(new TreeSet<>(symbols)), listener);
            subs.add(s);
            return s;
        }

        synchronized SortedSet<Symbol> registered() {
            SortedSet<Symbol> out = new TreeSet<>();
            subs.forEach(s -> out.addAll(s.symbols));
            return out;
        }

        void emit(MarketEvent e) {
            for (Sub s : new ArrayList<>(subs)) {
                if (s.symbols.contains(e.symbol())) {
                    s.listener.onEvent(e);
                }
            }
        }

        void set(ConnectionState next) {
            state = next;
            fireOnly(next);
        }

        /** 상태는 그대로 두고 알림만 보낸다(늦게 도착한 알림 흉내). */
        void fireOnly(ConnectionState reported) {
            listeners.forEach(l -> l.onStateChanged(id, reported));
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public Optional<java.time.Instant> lastReceivedAt() {
            return Optional.ofNullable(lastReceived);
        }

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public void addStateListener(SourceStateListener listener) {
            listeners.add(listener);
        }

        @Override
        public void close() {
            closed = true;
        }

        private final class Sub implements Subscription {
            final SortedSet<Symbol> symbols;
            final MarketEventListener listener;

            Sub(SortedSet<Symbol> symbols, MarketEventListener listener) {
                this.symbols = symbols;
                this.listener = listener;
            }

            @Override
            public SortedSet<Symbol> symbols() {
                return symbols;
            }

            @Override
            public void close() {
                subs.remove(this);
            }
        }
    }
}
