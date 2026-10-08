package com.autotrade.app.gateway;

import com.autotrade.broker.BrokerCapability;
import com.autotrade.broker.BrokerPlugin;
import com.autotrade.broker.BrokerStatus;
import com.autotrade.config.MarketDataMode;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.marketdata.ConnectionState;
import com.autotrade.marketdata.LiveMarketDataSource;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.marketdata.Subscription;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 한 시장의 실시간 소스 운용(ADR-002 2.2): 소스 배분, 상태 변화에 따른 즉시 재배분(FAILOVER), 녹화 연결.
 *
 * <ul>
 *   <li>소스 상태가 바뀌면 바로 다시 배분한다. 끊긴 소스의 종목은 다음 소스로 옮긴다(MD-03 "3초 내 전환"은
 *       끊김 감지 시점부터 재배분까지가 동기 호출이라 지연이 없다. 끊김 감지 자체는 소켓 종료 즉시 또는 무응답 판단 시간).</li>
 *   <li>종목을 다른 소스로 옮길 때는 옛 구독을 먼저 닫고 새로 구독한다(같은 종목이 두 소스에서 동시에 녹화되지 않게).
 *       그 지점에 {@link DataGap}(SOURCE_SWITCH)을 녹화한다. 시작은 옛 소스가 끊긴 시각(끊김이 원인일 때) 또는 지금.</li>
 *   <li>주문 API를 호출하지 않는다. 플러그인의 주문 출구를 쓰지 않는다(MD-01).</li>
 * </ul>
 * 상태 변화 알림과 공개 메서드는 이 객체의 잠금 하나로 직렬화한다.
 */
final class MarketHub implements AutoCloseable {

    private static final Logger LOG = System.getLogger(MarketHub.class.getName());

    /** 게이트웨이가 직접 녹화하는 이벤트(SOURCE_SWITCH 공백)의 출처 표시. */
    static final String SOURCE_ID = "GATEWAY";
    private static final SortedSet<Symbol> EMPTY = java.util.Collections.emptySortedSet();

    private final Market market;
    private final MarketDataMode mode;
    private final List<BrokerPlugin> byPriority;
    private final WatchList watch;
    private final MarketEventRecorder recorder;
    private final Clock clock;
    private final Map<BrokerId, Subscription> subscriptions = new EnumMap<>(BrokerId.class);
    private final Map<Symbol, BrokerId> owner = new TreeMap<>();
    private final Map<BrokerId, Instant> downSince = new EnumMap<>(BrokerId.class);
    private SourcePlan current;
    private boolean closed;

    MarketHub(Market market, MarketDataMode mode, List<BrokerPlugin> byPriority, WatchList watch,
            MarketEventRecorder recorder, Clock clock) {
        this.market = Objects.requireNonNull(market, "market");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.byPriority = List.copyOf(byPriority);
        this.watch = Objects.requireNonNull(watch, "watch").forMarket(market);
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 배분하고 소스를 연결한다. */
    void start() {
        for (BrokerPlugin p : byPriority) {
            live(p).ifPresent(src -> src.addStateListener((id, st) -> onState(p.brokerId(), st)));
        }
        replan();
        for (BrokerPlugin p : byPriority) {
            live(p).ifPresent(LiveMarketDataSource::start);
        }
    }

    private Optional<LiveMarketDataSource> live(BrokerPlugin p) {
        return p.marketData(market).filter(LiveMarketDataSource.class::isInstance).map(LiveMarketDataSource.class::cast);
    }

    private synchronized void onState(BrokerId broker, ConnectionState state) {
        if (closed) {
            return;
        }
        if (state == ConnectionState.DISCONNECTED) {
            downSince.putIfAbsent(broker, clock.instant());
        } else if (state == ConnectionState.CONNECTED) {
            downSince.remove(broker);
        }
        replan();
    }

    /** 현재 상태로 다시 배분하고, 바뀐 종목만 옮긴다. */
    synchronized SourcePlan replan() {
        List<SourcePlanner.Candidate> candidates = new ArrayList<>();
        for (BrokerPlugin p : byPriority) {
            // 끊긴 소스는 다시 CONNECTED가 될 때까지 쓰지 않는다(재접속 시도 중 CONNECTING은 아직 못 쓰는 상태)
            boolean usable = p.capabilities().contains(BrokerCapability.REALTIME_QUOTE)
                    && p.status() != BrokerStatus.DEGRADED && p.status() != BrokerStatus.NOT_CONFIGURED
                    && !downSince.containsKey(p.brokerId())
                    && p.marketData(market).isPresent();
            int capacity = p.marketData(market).map(MarketDataSource::maxSubscriptions).orElse(0);
            candidates.add(new SourcePlanner.Candidate(p.brokerId(), usable, capacity));
        }
        SourcePlan plan = SourcePlanner.plan(mode, candidates, watch);
        if (plan.equals(current)) {
            return plan;
        }
        apply(plan);
        if (!plan.unservedHeld().isEmpty()) {
            LOG.log(Level.ERROR, "{0} 보유 종목 {1}개를 받을 실시간 소스가 없습니다(한도 초과 또는 전 소스 장애): {2}",
                    market, plan.unservedHeld().size(), plan.unservedHeld());
        }
        if (!plan.excluded().isEmpty()) {
            LOG.log(Level.WARNING, "{0} 구독 한도로 후보 {1}개 제외", market, plan.excluded().size());
        }
        LOG.log(Level.INFO, "{0} 시세 배분 {1}: {2}", market, plan.mode(), summary(plan));
        current = plan;
        return plan;
    }

    private void apply(SourcePlan plan) {
        Instant now = clock.instant();
        Map<Symbol, BrokerId> next = new TreeMap<>();
        plan.assignments().forEach((b, syms) -> syms.forEach(s -> next.put(s, b)));
        // 1) 빠지거나 다른 증권사로 옮겨 가는 종목을 먼저 뺀다. 같은 종목이 두 소스에서 동시에 녹화되지 않고,
        //    다음 단계의 새 구독이 증권사 한도를 넘지 않는다(남는 종목은 새 구독을 먼저 만들고 옛 것을 닫아 끊기지 않게).
        for (BrokerPlugin p : byPriority) {
            Subscription old = subscriptions.get(p.brokerId());
            if (old == null) {
                continue;
            }
            SortedSet<Symbol> keep = new TreeSet<>(old.symbols());
            keep.retainAll(plan.assignments().getOrDefault(p.brokerId(), EMPTY));
            if (keep.equals(old.symbols())) {
                continue;
            }
            if (keep.isEmpty()) {
                subscriptions.remove(p.brokerId());
            } else {
                subscriptions.put(p.brokerId(), subscribe(p, keep));
            }
            old.close();
        }
        // 2) 새로 맡는 종목을 더한다
        for (BrokerPlugin p : byPriority) {
            SortedSet<Symbol> want = plan.assignments().getOrDefault(p.brokerId(), EMPTY);
            Subscription old = subscriptions.get(p.brokerId());
            if (want.isEmpty() || (old != null && old.symbols().equals(want))) {
                continue;
            }
            subscriptions.put(p.brokerId(), subscribe(p, want));
            if (old != null) {
                old.close();
            }
        }
        // 3) 소스가 바뀐 종목에 전환 표시. 종목 순서로 녹화해 결과가 결정적이다
        for (Map.Entry<Symbol, BrokerId> e : next.entrySet()) {
            BrokerId prev = owner.get(e.getKey());
            if (prev != null && prev != e.getValue()) {
                Instant start = downSince.getOrDefault(prev, now);
                recorder.offer(SOURCE_ID, new DataGap(e.getKey(), 0, now, now, start.isAfter(now) ? now : start, now,
                        DataGap.GapReason.SOURCE_SWITCH));
            }
        }
        owner.clear();
        owner.putAll(next);
    }

    private Subscription subscribe(BrokerPlugin p, SortedSet<Symbol> symbols) {
        MarketDataSource src = p.marketData(market).orElseThrow();
        return src.subscribe(symbols, recorder.listenerFor(src.sourceId()));
    }

    private static String summary(SourcePlan plan) {
        StringBuilder sb = new StringBuilder();
        plan.assignments().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue().size()).append("종목 "));
        return sb.toString().strip();
    }

    synchronized SourcePlan currentPlan() {
        return current;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        subscriptions.values().forEach(Subscription::close);
        subscriptions.clear();
        for (BrokerPlugin p : byPriority) {
            live(p).ifPresent(LiveMarketDataSource::close);
        }
    }
}
