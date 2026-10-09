package com.autotrade.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.app.gateway.SourcePlanner.Candidate;
import com.autotrade.config.MarketDataMode;
import com.autotrade.domain.account.BrokerId;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SourcePlannerTest {

    static List<Symbol> krx(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(i -> new Symbol(Market.KRX, String.format("%06d", i))).toList();
    }

    @Test
    void AUTO는_두_소스가_있으면_SHARDED로_1순위부터_채운다() {
        WatchList w = new WatchList(krx(1, 2), krx(10, 4));
        SourcePlan p = SourcePlanner.plan(MarketDataMode.AUTO,
                List.of(new Candidate(BrokerId.KIWOOM, true, 3), new Candidate(BrokerId.KIS, true, 2)), w);

        assertThat(p.mode()).isEqualTo(MarketDataMode.SHARDED);
        assertThat(p.assignments().get(BrokerId.KIWOOM)).containsExactly(krx(1, 2).get(0), krx(1, 2).get(1), krx(10, 1).get(0));
        assertThat(p.assignments().get(BrokerId.KIS)).containsExactlyElementsOf(krx(11, 2));
        assertThat(p.excluded()).containsExactly(krx(13, 1).get(0));
        assertThat(p.unservedHeld()).isEmpty();
    }

    @Test
    void 쓸_수_있는_소스가_하나면_FAILOVER로_그_소스만_쓴다() {
        WatchList w = new WatchList(krx(1, 1), krx(10, 2));
        SourcePlan p = SourcePlanner.plan(MarketDataMode.AUTO,
                List.of(new Candidate(BrokerId.KIWOOM, false, 200), new Candidate(BrokerId.KIS, true, 20)), w);

        assertThat(p.mode()).isEqualTo(MarketDataMode.FAILOVER);
        assertThat(p.assignments()).containsOnlyKeys(BrokerId.KIS);
        assertThat(p.assignments().get(BrokerId.KIS)).hasSize(3);
    }

    @Test
    void FAILOVER_지정이면_두_소스가_있어도_1순위만_쓴다() {
        SourcePlan p = SourcePlanner.plan(MarketDataMode.FAILOVER,
                List.of(new Candidate(BrokerId.KIWOOM, true, 2), new Candidate(BrokerId.KIS, true, 20)),
                new WatchList(List.of(), krx(1, 3)));

        assertThat(p.assignments()).containsOnlyKeys(BrokerId.KIWOOM);
        assertThat(p.excluded()).containsExactly(krx(3, 1).get(0));
    }

    @Test
    void 보유_종목은_후보보다_먼저_배정되고_한도를_넘으면_경보_대상으로_남는다() {
        WatchList w = new WatchList(krx(1, 3), krx(10, 2));
        SourcePlan p = SourcePlanner.plan(MarketDataMode.AUTO, List.of(new Candidate(BrokerId.KIS, true, 2)), w);

        assertThat(p.assignments().get(BrokerId.KIS)).containsExactlyElementsOf(krx(1, 2));
        assertThat(p.unservedHeld()).containsExactly(krx(3, 1).get(0));
        assertThat(p.excluded()).containsExactlyElementsOf(krx(10, 2));
    }

    @Test
    void FAILOVER라도_넘치는_보유_종목은_다음_소스로_보내고_후보는_보내지_않는다() {
        SourcePlan p = SourcePlanner.plan(MarketDataMode.FAILOVER,
                List.of(new Candidate(BrokerId.KIWOOM, true, 2), new Candidate(BrokerId.KIS, true, 20)),
                new WatchList(krx(1, 3), krx(10, 2)));

        assertThat(p.assignments().get(BrokerId.KIWOOM)).containsExactlyElementsOf(krx(1, 2));
        assertThat(p.assignments().get(BrokerId.KIS)).containsExactly(krx(3, 1).get(0));
        assertThat(p.unservedHeld()).isEmpty();
        assertThat(p.excluded()).containsExactlyElementsOf(krx(10, 2));
    }

    @Test
    void 모든_소스가_장애면_보유_종목_전체가_경보_대상이다() {
        SourcePlan p = SourcePlanner.plan(MarketDataMode.AUTO,
                List.of(new Candidate(BrokerId.KIWOOM, false, 200), new Candidate(BrokerId.KIS, false, 20)),
                new WatchList(krx(1, 2), List.of()));

        assertThat(p.assignments()).isEmpty();
        assertThat(p.unservedHeld()).hasSize(2);
    }

    @Test
    void 보유와_후보에_같이_있는_종목은_보유로_한_번만_센다() {
        Symbol s = krx(1, 1).get(0);
        WatchList w = new WatchList(List.of(s), List.of(s));
        assertThat(w.candidates()).isEmpty();
    }
}
