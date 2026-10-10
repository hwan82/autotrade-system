package com.autotrade.selection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CombinationTest {

    private static final String A64 = "a".repeat(64);
    private static final String B64 = "b".repeat(64);

    @Test
    void 조합_해시는_ADR과_DB_함수의_골든_값과_같다() {
        // ADR-007 2.2 표: Python hashlib과 PostgreSQL 16 combination_hash_v1에서 같게 나온 값
        assertThat(Combination.combinationHash("none", A64))
                .isEqualTo("2ce6b7ca328e5f40cb3fce187e4f81f2adcea5a3c89743d929e5c95b4a4ff6be");
        assertThat(Combination.combinationHash(B64, A64))
                .isEqualTo("0bc30bbaf8112494455b56cbf4f361c2541ce74dd23e58b8c416c64b368b8945");
        // 순서 고정: 선정과 전략을 바꾸면 다른 해시
        assertThat(Combination.combinationHash(A64, B64)).isNotEqualTo(Combination.combinationHash(B64, A64));
    }

    @Test
    void 선정_없음은_none_표식이고_선정이_있으면_선정_해시를_쓴다() {
        StrategyProfile p = profile(TradingStyle.SWING);
        Combination none = Combination.of(p);
        assertThat(none.hasSelection()).isFalse();
        assertThat(none.selectionHashOrNone()).isEqualTo(Combination.NONE);
        assertThat(none.selectionKeyOrNone()).isEqualTo("none");
        assertThat(none.combinationHash()).isEqualTo(Combination.combinationHash("none", p.profileHash()));

        SelectionProfile s = SelectionProfileTest.swing("sel", Map.of());
        Combination with = Combination.of(s, p);
        assertThat(with.selectionHashOrNone()).isEqualTo(s.selectionHash()).hasSize(64);
        assertThat(with.selectionKeyOrNone()).isEqualTo("sel@1");
        assertThat(with.combinationHash()).isEqualTo(Combination.combinationHash(s.selectionHash(), p.profileHash()))
                .isNotEqualTo(none.combinationHash());
    }

    @Test
    void 선정과_전략의_시장이나_스타일이_다르면_거부한다() {
        SelectionProfile swingSel = SelectionProfileTest.swing("sel", Map.of());
        StrategyProfile position = profile(TradingStyle.POSITION);
        assertThatThrownBy(() -> Combination.of(swingSel, position)).hasMessageContaining("스타일");
        StrategyProfile us = new StrategyProfile("p", 1, Market.US, TradingStyle.SWING, new TreeMap<>());
        assertThatThrownBy(() -> Combination.of(swingSel, us)).hasMessageContaining("시장");
    }

    private static StrategyProfile profile(TradingStyle style) {
        return new StrategyProfile("p", 1, Market.KRX, style, new TreeMap<>(Map.of("entry.logic", "x")));
    }
}
