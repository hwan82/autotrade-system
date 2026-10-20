package com.autotrade.selection.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.selection.result.SessionSelectionResult;
import com.autotrade.strategy.TradingStyle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 장중 순위 선정 계산(D-127) 골든 테스트: 손 계산 값, 경계값, 순위 자르기, 결측·누락 사유, 내용 해시 결정론. */
class SessionSelectionCalculatorTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 14);

    private static SelectionProfile profile(int maxSymbols) {
        TreeMap<String, String> p = new TreeMap<>(Map.of("hypothesis", "가설", "refresh", "INTRADAY_AT", "at", "09:30",
                "max-symbols", Integer.toString(maxSymbols),
                "gate.01.metric", "session-return", "gate.01.op", "GE", "gate.01.value", "0.01",
                "score.metric", "session-turnover", "score.order", "DESC"));
        return new SelectionProfile("kr-test-at", 1, Market.KRX, TradingStyle.INTRADAY, p);
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    /** 손 계산 입력(구간 체결 순서대로). */
    static Map<String, SessionStats> stats() {
        // A: 10000×400 → 10200×100 → 9900×100 → 10100×400. 거래대금 10,050,000, 수익률 +1%(경계 GE 통과), 변동폭 0.03
        SessionStats a = SessionStats.of(bd(10000), 400).plus(bd(10200), 100).plus(bd(9900), 100).plus(bd(10100), 400);
        // B: 5000×1000 → 5100×1000. 거래대금 10,100,000, +2%
        SessionStats b = SessionStats.of(bd(5000), 1000).plus(bd(5100), 1000);
        // C: 20000×10 → 21000×1000. 거래대금 21,200,000, +5%
        SessionStats c = SessionStats.of(bd(20000), 10).plus(bd(21000), 1000);
        // D: 1000×100 → 990×100. −1% → 조건 탈락
        SessionStats d = SessionStats.of(bd(1000), 100).plus(bd(990), 100);
        // F: 구간 누락이 있던 종목(값이 좋아도 떨어진다)
        SessionStats f = SessionStats.of(bd(1000), 1_000_000).plus(bd(1100), 1_000_000);
        return Map.of("A", a, "B", b, "C", c, "D", d, "F", f);
    }

    @Test
    void 손_계산대로_순위를_매기고_자른다() {
        SessionSelectionResult r = SessionSelectionCalculator.calculate(profile(2), D, List.of("A", "B", "C", "D", "E", "F"),
                stats(), Set.of("F"));
        assertThat(r.selected()).extracting(SelectionResult.Selected::code).containsExactly("C", "B");
        assertThat(r.at()).isEqualTo(LocalTime.of(9, 30));
        assertThat(r.universe()).isEqualTo(6);
        Map<String, SelectionResult.Evaluation> e = new TreeMap<>();
        r.evaluations().forEach(x -> e.put(x.code(), x));
        assertThat(e.get("C").values()).containsEntry("gate.01", "0.05").containsEntry("score", "21200000");
        assertThat(e.get("B").values()).containsEntry("gate.01", "0.02").containsEntry("score", "10100000");
        assertThat(e.get("A").reasons()).containsExactly(SelectionCalculator.RANK_CUTOFF);
        assertThat(e.get("A").values()).containsEntry("gate.01", "0.01").containsEntry("score", "10050000");
        assertThat(e.get("D").reasons()).containsExactly("GATE_01");
        assertThat(e.get("D").values()).containsEntry("gate.01", "-0.01");
        assertThat(e.get("E").reasons()).containsExactly("GATE_01_NO_DATA");
        assertThat(e.get("F").reasons()).containsExactly(SessionSelectionCalculator.SESSION_GAP);
        assertThat(e.get("F").values()).isEmpty();
    }

    @Test
    void 장중_지표_정의() {
        SessionStats a = stats().get("A");
        assertThat(SessionSelectionCalculator.value(Metric.SESSION_TURNOVER, a)).isEqualByComparingTo("10050000");
        assertThat(SessionSelectionCalculator.value(Metric.SESSION_RETURN, a)).isEqualByComparingTo("0.01");
        assertThat(SessionSelectionCalculator.value(Metric.SESSION_RANGE, a)).isEqualByComparingTo("0.03");
        assertThat(a.volume()).isEqualTo(1000);
        assertThat(a.trades()).isEqualTo(4);
        assertThat(SessionSelectionCalculator.value(Metric.SESSION_RETURN, null)).isNull();
        // 한 건뿐이면 수익률·변동폭 0
        assertThat(SessionSelectionCalculator.value(Metric.SESSION_RANGE, SessionStats.of(bd(7), 1))).isEqualByComparingTo("0");
    }

    @Test
    void 같은_입력이면_유니버스_순서와_무관하게_같은_내용_해시() {
        String h1 = SessionSelectionCalculator.calculate(profile(2), D, List.of("A", "B", "C", "D", "E", "F"), stats(),
                Set.of("F")).contentHash();
        String h2 = SessionSelectionCalculator.calculate(profile(2), D, List.of("F", "E", "D", "C", "B", "A"), stats(),
                Set.of("F")).contentHash();
        assertThat(h1).isEqualTo(h2).matches("[0-9a-f]{64}");
        assertThat(SessionSelectionCalculator.calculate(profile(3), D, List.of("A", "B", "C", "D", "E", "F"), stats(),
                Set.of("F")).contentHash()).isNotEqualTo(h1);
        assertThat(SessionSelectionCalculator.calculate(profile(2), D, List.of("A", "B", "C", "D", "E", "F"), stats(),
                Set.of()).selected()).extracting(SelectionResult.Selected::code).containsExactly("F", "C");
        assertThat(SessionSelectionCalculator.calculate(profile(2), D, List.of("A"), stats(), Set.of()).canonicalJson())
                .startsWith("{\"at\":\"09:30\",\"date\":\"2026-10-14\",\"evaluations\":[")
                .contains("\"kind\":\"session-selection-result\"");
    }

    @Test
    void 장중_순위_선정_프로필만_받고_일봉_계산기는_장중_순위_선정을_받지_않는다() {
        SelectionProfile daily = SelectionCalculatorTest.repoProfile("kr-intraday-hot");
        assertThatThrownBy(() -> SessionSelectionCalculator.calculate(daily, D, List.of("A"), stats(), Set.of()))
                .hasMessageContaining("INTRADAY_AT");
        SelectionProfile repo = SelectionCalculatorTest.repoProfile("kr-intraday-open-momentum");
        assertThatThrownBy(() -> new SelectionCalculator(new FixtureData()).calculate(repo, LocalDate.of(2026, 3, 3)))
                .hasMessageContaining("INTRADAY_AT");
    }
}
