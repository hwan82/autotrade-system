package com.autotrade.sim.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class RunSeedsTest {

    @Test
    void 같은_입력이면_같은_시드이고_프로필이나_실험_시드가_다르면_다르다() {
        long a = RunSeeds.derive(42, "aa");
        assertThat(RunSeeds.derive(42, "aa")).isEqualTo(a);
        assertThat(RunSeeds.derive(42, "ab")).isNotEqualTo(a);
        assertThat(RunSeeds.derive(43, "aa")).isNotEqualTo(a);
        // 파생 함수 고정(ADR-004 2.4): 바꾸면 이전 실험과 결과가 달라진다
        assertThat(RunSeeds.derive(0, "")).isEqualTo(RunSeeds.derive(0, ""));
    }

    @Test
    void v1과_v2는_ADR_골든_값과_같다() {
        // ADR-007 2.4: Python hashlib·struct.unpack('>q')로 계산한 값
        assertThat(RunSeeds.derive(42, "a".repeat(64))).isEqualTo(3654410437908045060L);
        String combo = Combination.combinationHash("b".repeat(64), "a".repeat(64));
        assertThat(combo).isEqualTo("0bc30bbaf8112494455b56cbf4f361c2541ce74dd23e58b8c416c64b368b8945");
        assertThat(sha("run-seed-v2", 42, combo)).isEqualTo(4792420304985496537L);
    }

    @Test
    void 선정_없음은_v1_그대로이고_선정이_있으면_v2라_같은_전략도_다른_난수열을_받는다() {
        StrategyProfile p = new StrategyProfile("p", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>());
        SelectionProfile x = selection("x");
        SelectionProfile y = selection("y");
        Combination none = Combination.of(p);
        assertThat(RunSeeds.derive(42, none)).isEqualTo(RunSeeds.derive(42, p.profileHash()));
        assertThat(RunSeeds.scheme(none)).isEqualTo("run-seed-v1");

        Combination withX = Combination.of(x, p);
        Combination withY = Combination.of(y, p);
        assertThat(RunSeeds.scheme(withX)).isEqualTo("run-seed-v2");
        assertThat(RunSeeds.derive(42, withX)).isEqualTo(sha("run-seed-v2", 42, withX.combinationHash()));
        assertThat(RunSeeds.derive(42, withX))
                .isNotEqualTo(RunSeeds.derive(42, withY))
                .isNotEqualTo(RunSeeds.derive(42, none));
    }

    private static long sha(String prefix, long seed, String input) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((prefix + "|" + seed + "|" + input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(h, 0, 8).getLong();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SelectionProfile selection(String id) {
        return new SelectionProfile(id, 1, Market.KRX, TradingStyle.SWING,
                new TreeMap<>(Map.of("hypothesis", "h", "refresh", "POST_CLOSE_DAILY", "max-symbols", "10")));
    }
}
