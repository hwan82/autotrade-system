package com.autotrade.app.gateway;

import com.autotrade.config.MarketDataMode;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Symbol;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * 한 시장의 시세 소스 배분 결과(D-009, MD-04).
 *
 * @param mode 실제로 적용한 방식(FAILOVER 또는 SHARDED)
 * @param assignments 증권사 → 구독 종목. 종목은 한 증권사에만 배정된다
 * @param excluded 한도 때문에 뺀 후보 종목(우선순위 낮은 것부터)
 * @param unservedHeld 받을 소스가 없거나 한도를 넘어 감시하지 못하는 보유 종목. 비어 있지 않으면 경보 대상이다
 */
public record SourcePlan(
        MarketDataMode mode,
        Map<BrokerId, SortedSet<Symbol>> assignments,
        List<Symbol> excluded,
        List<Symbol> unservedHeld) {

    public SourcePlan {
        assignments = Map.copyOf(assignments);
        excluded = List.copyOf(excluded);
        unservedHeld = List.copyOf(unservedHeld);
    }
}
