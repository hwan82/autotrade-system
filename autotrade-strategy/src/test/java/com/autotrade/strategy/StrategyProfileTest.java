package com.autotrade.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class StrategyProfileTest {

    @Test
    void 해시는_파라미터_입력_순서와_무관하다() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("entry.volume-surge", "3.0");
        a.put("exit.stop-loss-pct", "2.5");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("exit.stop-loss-pct", "2.5");
        b.put("entry.volume-surge", "3.0");

        assertThat(profile(a).profileHash()).isEqualTo(profile(b).profileHash());
    }

    @Test
    void 값이_하나만_달라도_해시가_바뀐다() {
        String base = profile(Map.of("exit.stop-loss-pct", "2.5")).profileHash();
        assertThat(profile(Map.of("exit.stop-loss-pct", "2.50")).profileHash()).isNotEqualTo(base);
        assertThat(new StrategyProfile("vol-surge", 2, Market.KRX, TradingStyle.INTRADAY,
                        new TreeMap<>(Map.of("exit.stop-loss-pct", "2.5"))).profileHash())
                .isNotEqualTo(base);
    }

    @Test
    void 구분자를_값에_넣어도_다른_프로필과_충돌하지_않는다() {
        String h1 = profile(Map.of("a", "1\n2:p.b=1")).profileHash();
        String h2 = profile(Map.of("a", "1", "b", "1")).profileHash();
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void 해시_값은_고정된_골든값과_일치한다() {
        // 해시 규칙이 바뀌면 기존 실험 결과와 연결이 끊긴다. 바꿔야 하면 ADR로 남긴다.
        assertThat(profile(Map.of("exit.stop-loss-pct", "2.5")).profileHash())
                .isEqualTo(GoldenHash.VOL_SURGE_V1);
    }

    @Test
    void 잘못된_ID와_키는_거부한다() {
        assertThatThrownBy(() -> new StrategyProfile("Vol Surge", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile(Map.of("Stop Loss", "1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StrategyProfile profile(Map<String, String> params) {
        return new StrategyProfile("vol-surge", 1, Market.KRX, TradingStyle.INTRADAY, new TreeMap<>(params));
    }

    private static final class GoldenHash {
        static final String VOL_SURGE_V1 = "33085dd93a2b9393fafb7bb7bc095ef5921d66183e05f07ddafd6520604436de";
    }
}
