package com.autotrade.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.Combination;
import com.autotrade.selection.profile.SelectionProfile;
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
        assertThat(spec.combinations()).extracting(c -> c.profile()).containsExactly(b, a);
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
    void 같은_전략_프로필은_선정이_다르면_여러_번_들어갈_수_있고_같은_조합은_거부한다() {
        StrategyProfile a = profile("a", Market.KRX);
        SelectionProfile x = selection("x", "10");
        SelectionProfile y = selection("y", "10");
        ExperimentSpec spec = combos(List.of(Combination.of(a), Combination.of(x, a), Combination.of(y, a)));
        assertThat(spec.combinations()).hasSize(3);
        assertThat(spec.hasSelections()).isTrue();
        assertThat(spec.market()).isEqualTo(Market.KRX);

        assertThatThrownBy(() -> combos(List.of(Combination.of(x, a), Combination.of(x, a))))
                .hasMessageContaining("같은 조합");
        assertThatThrownBy(() -> combos(List.of(Combination.of(a), Combination.of(a))))
                .hasMessageContaining("같은 조합");
        // 같은 선정 id@version에 다른 내용
        assertThatThrownBy(() -> combos(List.of(Combination.of(x, a),
                Combination.of(selection("x", "20"), profile("b", Market.KRX)))))
                .hasMessageContaining("선정 프로필");
        // 같은 전략 id@version에 다른 내용(선정이 달라도)
        StrategyProfile otherA =
                new StrategyProfile("a", 1, Market.KRX, TradingStyle.SWING, new TreeMap<>(Map.of("k", "other")));
        assertThatThrownBy(() -> combos(List.of(Combination.of(x, a), Combination.of(y, otherA))))
                .hasMessageContaining("다른 내용");
    }

    @Test
    void 호환_팩토리는_프로필마다_선정_없는_조합을_만든다() {
        StrategyProfile a = profile("a", Market.KRX);
        ExperimentSpec spec = spec(D1, D2, SEED, List.of(a));
        assertThat(spec.combinations()).containsExactly(Combination.of(a));
        assertThat(spec.hasSelections()).isFalse();
    }

    @Test
    void 시드머니_통화는_시장_통화와_같아야_한다() {
        assertThatThrownBy(() -> spec(D1, D2, Money.of(10_000, Money.USD), List.of(profile("a", Market.KRX))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExperimentSpec spec(LocalDate from, LocalDate to, Money seed, List<StrategyProfile> profiles) {
        return ExperimentSpec.ofProfiles(new ExperimentId("EXP-TEST-01"), from, to, 42L, seed, "cost-kr-default", "rec-test", ClockMode.REPLAY, profiles);
    }

    private static ExperimentSpec combos(List<Combination> combinations) {
        return new ExperimentSpec(new ExperimentId("EXP-TEST-02"), D1, D2, 42L, SEED, "cost-kr-default", "rec-test",
                ClockMode.REPLAY, combinations);
    }

    private static SelectionProfile selection(String id, String maxSymbols) {
        return new SelectionProfile(id, 1, Market.KRX, TradingStyle.SWING, new TreeMap<>(Map.of(
                "hypothesis", "h", "refresh", "POST_CLOSE_DAILY", "max-symbols", maxSymbols)));
    }

    private static StrategyProfile profile(String id, Market market) {
        return new StrategyProfile(id, 1, market, TradingStyle.SWING, new TreeMap<>(Map.of("k", "v")));
    }
}
