package com.autotrade.selection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SelectionProfileTest {

    static SelectionProfile swing(String id, Map<String, String> extra) {
        TreeMap<String, String> p = new TreeMap<>(Map.of(
                "hypothesis", "h", "refresh", "POST_CLOSE_DAILY", "max-symbols", "30"));
        p.putAll(extra);
        return new SelectionProfile(id, 1, Market.KRX, TradingStyle.SWING, p);
    }

    @Test
    void 해시는_골든_값과_같고_같은_내용의_전략_프로필_해시와_다르다() {
        SelectionProfile s = swing("kr-vol-surge-quiet", Map.of());
        // Python hashlib으로 따로 계산한 값(ADR-007 2.1 인코딩: kind 줄 + 전략 프로필과 같은 길이 접두 인코딩)
        assertThat(s.selectionHash()).isEqualTo("ba146f69db6ed61093c1ec0402d951f0baaca80c136958552ee5b6d3d76e777a");
        StrategyProfile sameContent = new StrategyProfile(s.id(), s.version(), s.market(), s.style(), s.parameters());
        assertThat(sameContent.profileHash())
                .isEqualTo("6072ea5832a17dc04b7d24af5162b1418a1dd7fb7c96428184f700efdb971247")
                .isNotEqualTo(s.selectionHash());
    }

    @Test
    void 파라미터_입력_순서와_무관하고_값_하나만_달라도_해시가_바뀐다() {
        SelectionProfile a = swing("sel", Map.of("gate.01.metric", "volume-ratio", "gate.01.value", "3.0"));
        TreeMap<String, String> reversed = new TreeMap<>(java.util.Comparator.reverseOrder());
        reversed.putAll(a.parameters());
        SelectionProfile b = new SelectionProfile("sel", 1, Market.KRX, TradingStyle.SWING, reversed);
        assertThat(b.selectionHash()).isEqualTo(a.selectionHash());
        assertThat(swing("sel", Map.of("gate.01.metric", "volume-ratio", "gate.01.value", "3.00")).selectionHash())
                .isNotEqualTo(a.selectionHash());
    }

    @Test
    void 예약_키를_검사한다() {
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.SWING,
                new TreeMap<>(Map.of("refresh", "POST_CLOSE_DAILY", "max-symbols", "3"))))
                .hasMessageContaining("hypothesis");
        assertThatThrownBy(() -> swing("s", Map.of("hypothesis", " "))).hasMessageContaining("hypothesis");
        assertThatThrownBy(() -> swing("s", Map.of("refresh", "HOURLY"))).hasMessageContaining("HOURLY");
        assertThatThrownBy(() -> swing("s", Map.of("max-symbols", "0"))).hasMessageContaining("max-symbols");
        assertThatThrownBy(() -> swing("s", Map.of("max-symbols", "3.5"))).hasMessageContaining("max-symbols");
        assertThat(swing("s", Map.of("max-symbols", "7")).maxSymbols()).isEqualTo(7);
        assertThat(swing("s", Map.of("refresh", "POST_CLOSE_WEEKLY")).refresh())
                .isEqualTo(SelectionRefresh.POST_CLOSE_WEEKLY);
    }

    @Test
    void 단타는_장전_갱신만_스윙과_중기는_장_마감_뒤_갱신만() {
        TreeMap<String, String> p = new TreeMap<>(Map.of("hypothesis", "h", "max-symbols", "5"));
        p.put("refresh", "PRE_OPEN_DAILY");
        assertThat(new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, p).refresh())
                .isEqualTo(SelectionRefresh.PRE_OPEN_DAILY);
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.SWING, p))
                .hasMessageContaining("PRE_OPEN_DAILY");
        p.put("refresh", "POST_CLOSE_WEEKLY");
        assertThat(new SelectionProfile("s", 1, Market.KRX, TradingStyle.POSITION, p).style())
                .isEqualTo(TradingStyle.POSITION);
        assertThatThrownBy(() -> new SelectionProfile("s", 1, Market.KRX, TradingStyle.INTRADAY, p))
                .hasMessageContaining("POST_CLOSE_WEEKLY");
    }

    @Test
    void ID와_키_형식은_전략_프로필과_같다() {
        assertThatThrownBy(() -> swing("Bad_ID", Map.of())).hasMessageContaining("ID");
        assertThatThrownBy(() -> swing("s", Map.of("Gate", "1"))).hasMessageContaining("키");
        assertThatThrownBy(() -> new SelectionProfile("s", 0, Market.KRX, TradingStyle.SWING,
                swing("s", Map.of()).parameters())).hasMessageContaining("version");
    }
}
