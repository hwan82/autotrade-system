package com.autotrade.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ExperimentSpecTest {

    private static final LocalDate D1 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D2 = LocalDate.of(2026, 6, 30);
    private static final Money SEED = Money.of(10_000_000, Money.KRW);

    @Test
    void 정상_스펙은_프로필_순서를_유지한다() {
        StrategyProfile a = profile("a", Market.KRX);
        StrategyProfile b = profile("b", Market.KRX);
        ExperimentSpec spec = spec(D1, D2, SEED, List.of(b, a));
        assertThat(spec.profiles()).containsExactly(b, a);
    }

    @Test
    void 기간이_뒤집히면_거부한다() {
        assertThatThrownBy(() -> spec(D2, D1, SEED, List.of(profile("a", Market.KRX))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 시드머니는_양수여야_한다() {
        assertThatThrownBy(() -> spec(D1, D2, Money.zero(Money.KRW), List.of(profile("a", Market.KRX))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_프로필_중복과_시장_혼합을_거부한다() {
        assertThatThrownBy(() -> spec(D1, D2, SEED, List.of(profile("a", Market.KRX), profile("a", Market.KRX))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec(D1, D2, SEED, List.of(profile("a", Market.KRX), profile("b", Market.US))))
                .isInstanceOf(IllegalArgumentException.class);
        StrategyProfile sameKeyOtherContent =
                new StrategyProfile("a", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>(Map.of("k", "other")));
        assertThatThrownBy(() -> spec(D1, D2, SEED, List.of(profile("a", Market.KRX), sameKeyOtherContent)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec(D1, D2, SEED, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 시드머니_통화는_시장_통화와_같아야_한다() {
        assertThatThrownBy(() -> spec(D1, D2, Money.of(10_000, Money.USD), List.of(profile("a", Market.KRX))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExperimentSpec spec(LocalDate from, LocalDate to, Money seed, List<StrategyProfile> profiles) {
        return new ExperimentSpec(new ExperimentId("EXP-TEST-01"), from, to, 42L, seed, "cost-default", "rec-test", profiles);
    }

    private static StrategyProfile profile(String id, Market market) {
        return new StrategyProfile(id, 1, market, TradingStyle.SWING, new TreeMap<>(Map.of("k", "v")));
    }
}
