package com.autotrade.app.gateway;

import com.autotrade.config.MarketDataMode;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Symbol;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 시세 소스 배분 규칙(D-008·D-009, MD-03·MD-04, ADR-002 2.2). 순수 함수: 같은 입력이면 같은 결과.
 *
 * <ul>
 *   <li>후보 소스는 {@code market-data.priority} 순서이고, 쓸 수 있는(실시간 능력이 있고 DEGRADED가 아닌) 것만 고른다.</li>
 *   <li>AUTO: 쓸 수 있는 소스가 둘 이상이면 SHARDED, 하나면 FAILOVER. SHARDED를 지정해도 하나뿐이면 FAILOVER로 돈다.
 *       세 곳 동시 SHARDED는 D-009 범위 밖이라 앞의 두 곳만 쓴다.</li>
 *   <li>종목 순서: 보유 먼저, 그다음 후보 우선순위. 앞에서부터 1순위 소스 한도까지, 넘치면 2순위 소스로 채운다(SHARDED).</li>
 *   <li>한도를 넘는 후보는 뒤에서부터 뺀다. 보유 종목은 빼지 않는다: 한도를 넘는 보유 종목은 {@code unservedHeld}로 알린다
 *       (증권사 한도를 넘겨 구독할 수는 없으므로 경보로 대응한다).</li>
 * </ul>
 */
public final class SourcePlanner {

    /** 소스 후보. {@code usable}은 REALTIME_QUOTE 능력이 있고 DEGRADED·NOT_CONFIGURED가 아님. */
    public record Candidate(BrokerId broker, boolean usable, int capacity) {
        public Candidate {
            Objects.requireNonNull(broker, "broker");
            if (capacity < 0) {
                throw new IllegalArgumentException("capacity < 0");
            }
        }
    }

    private SourcePlanner() {}

    public static SourcePlan plan(MarketDataMode configured, List<Candidate> byPriority, WatchList watch) {
        Objects.requireNonNull(configured, "configured");
        List<Candidate> usable = byPriority.stream().filter(Candidate::usable).toList();
        MarketDataMode mode = switch (configured) {
            case AUTO, SHARDED -> usable.size() >= 2 ? MarketDataMode.SHARDED : MarketDataMode.FAILOVER;
            case FAILOVER -> MarketDataMode.FAILOVER;
        };
        List<Candidate> active = usable.subList(0, Math.min(usable.size(), mode == MarketDataMode.SHARDED ? 2 : 1));

        Map<BrokerId, SortedSet<Symbol>> assignments = new EnumMap<>(BrokerId.class);
        List<Symbol> excluded = new ArrayList<>();
        List<Symbol> unservedHeld = new ArrayList<>();
        int slot = 0;
        int used = 0;
        for (Symbol s : watch.held()) {
            while (slot < active.size() && used >= active.get(slot).capacity()) {
                slot++;
                used = 0;
            }
            if (slot >= active.size()) {
                unservedHeld.add(s);
                continue;
            }
            assignments.computeIfAbsent(active.get(slot).broker(), b -> new TreeSet<>()).add(s);
            used++;
        }
        for (Symbol s : watch.candidates()) {
            while (slot < active.size() && used >= active.get(slot).capacity()) {
                slot++;
                used = 0;
            }
            if (slot >= active.size()) {
                excluded.add(s);
                continue;
            }
            assignments.computeIfAbsent(active.get(slot).broker(), b -> new TreeSet<>()).add(s);
            used++;
        }
        return new SourcePlan(mode, assignments, excluded, unservedHeld);
    }
}
