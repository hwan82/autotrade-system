package com.autotrade.selection.calc;

import static com.autotrade.selection.calc.IntradaySelectionCalculatorTest.C;
import static com.autotrade.selection.calc.IntradaySelectionCalculatorTest.T;
import static com.autotrade.selection.calc.IntradaySelectionCalculatorTest.day;
import static com.autotrade.selection.calc.SelectionCalculatorTest.eval;
import static com.autotrade.selection.calc.SelectionCalculatorTest.reasons;
import static com.autotrade.selection.calc.SelectionCalculatorTest.repoProfile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 공시 지표 {@code disclosure-count}(ADR-012 3.2)와 예시 (라) {@code kr-intraday-news}(ADR-012 3.3)의 손 계산 골든 테스트.
 * T = 2026-03-16(월), c = 2026-03-13(금). w1 구간은 c 앞 거래일(03-12) 다음 날 ~ c = 03-13 하루다.
 */
class DisclosureSelectionCalculatorTest {

    static FixtureData.Disclosures coveredBI() {
        return new FixtureData.Disclosures().covered("B", LocalDate.of(2026, 1, 1), C).covered("I", LocalDate.of(2026, 1, 1), C);
    }

    static FixtureData newsFixture() {
        FixtureData d = new FixtureData();
        for (String c : List.of("800010", "800020", "800030", "800040", "800050", "800060", "800070")) {
            d.stock(c);
            // 앞 20거래일 종가 10,000·거래량 1,000,000, c 종가 10,300(거래대금은 c 거래량 × 10,300)
            day(d, c, "10000", "1", c.equals("800070") ? 100_000 : 1_000_000, "10500", "9900", "10300",
                    c.equals("800010") ? 3_000_000 : c.equals("800070") ? 485_436 : 2_000_000);
        }
        d.disclosures = coveredBI()
                .filed("800010", "B", C) // 주요사항보고 1건 → 통과, 급증 3 → 1위
                .filed("800020", "I", C).filed("800020", "I", C) // 거래소공시 2건 → 통과, 급증 2 → 2위
                .filed("800040", "B", LocalDate.of(2026, 3, 12)) // c−1: w1 구간 밖 → 0건
                .filed("800050", "B", LocalDate.of(2026, 3, 14)).filed("800050", "I", T) // c 뒤(주말·T 당일): 미래 정보
                .filed("800060", "D", C) // 지분공시: 프로필 유형 밖
                .filed("800070", "B", C); // 공시는 있으나 거래대금 10,300 × 485,436 = 49억 9,999만 800원 < 50억
        // 800030: 공시 없음 → 0건(정상값)
        return d;
    }

    @Test
    void 라_소식_있는_종목_골든() {
        SelectionProfile p = repoProfile("kr-intraday-news");
        assertThat(p.style()).isEqualTo(TradingStyle.INTRADAY);
        assertThat(p.refresh()).isEqualTo(SelectionRefresh.PRE_OPEN_DAILY);

        SelectionResult r = new SelectionCalculator(newsFixture()).calculate(p, T);

        assertThat(r.selected()).as("거래량 급증 큰 순").containsExactly(new SelectionResult.Selected("800010", 1),
                new SelectionResult.Selected("800020", 2));
        assertThat(reasons(r)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "800010", "", "800020", "",
                "800030", "GATE_01",
                "800040", "GATE_01",
                "800050", "GATE_01",
                "800060", "GATE_01",
                "800070", "GATE_02"));
        assertThat(eval(r, "800010").values()).isEqualTo(Map.of("gate.01", "1", "gate.02", "30900000000", "score", "3"));
        assertThat(eval(r, "800020").values()).containsEntry("gate.01", "2").containsEntry("score", "2");
        assertThat(eval(r, "800030").values()).as("공시 0건은 결측이 아니라 0").containsEntry("gate.01", "0");
        assertThat(eval(r, "800050").values()).containsEntry("gate.01", "0");
        assertThat(eval(r, "800070").values()).containsEntry("gate.02", "4999990800");
        assertThat(r.inputs().disclosuresSnapshotId()).isEqualTo("dart-disclosures-test");
        assertThat(r.inputs().disclosuresSha256()).isEqualTo("d".repeat(64));
        assertThat(r.inputs().disclosuresCoveredThrough()).isEqualTo(C);
        assertThat(r.canonicalJson()).contains("\"disclosures\":{\"covered_through\":\"2026-03-13\"");
    }

    @Test
    void T_당일과_c_뒤_접수_공시는_결과를_바꾸지_않는다() {
        FixtureData d = newsFixture();
        SelectionProfile p = repoProfile("kr-intraday-news");
        String before = new SelectionCalculator(d).calculate(p, T).contentHash();
        d.disclosures.filed("800030", "B", LocalDate.of(2026, 3, 15)).filed("800030", "B", T).filed("800030", "I",
                T.plusDays(1));
        d.disclosures.covered("B", LocalDate.of(2026, 1, 1), T.plusDays(5)).covered("I", LocalDate.of(2026, 1, 1), T.plusDays(5));
        SelectionResult after = new SelectionCalculator(d).calculate(p, T);
        assertThat(eval(after, "800030").values()).containsEntry("gate.01", "0");
        // 수집 구간 끝날은 결과 inputs에 남으므로 해시는 그것만큼 다르다. 평가·선정은 같다
        assertThat(after.selected()).isEqualTo(new SelectionCalculator(newsFixture()).calculate(p, T).selected());
        assertThat(after.evaluations()).isEqualTo(new SelectionCalculator(newsFixture()).calculate(p, T).evaluations());
        assertThat(after.contentHash()).isNotEqualTo(before);
        d.disclosures.filed("800030", "I", C);
        assertThat(eval(new SelectionCalculator(d).calculate(p, T), "800030").values()).as("c 접수는 셈")
                .containsEntry("gate.01", "1");
    }

    static SelectionProfile disclosureGate(String window, String types) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "공시 지표 시험용");
        p.put("refresh", "PRE_OPEN_DAILY");
        p.put("max-symbols", "50");
        p.put("gate.01.metric", "disclosure-count");
        if (window != null) {
            p.put("gate.01.window", window);
        }
        if (types != null) {
            p.put("gate.01.types", types);
        }
        p.put("gate.01.op", "GE");
        p.put("gate.01.value", "1");
        return new SelectionProfile("disclosure-test", 1, Market.KRX, TradingStyle.INTRADAY, p);
    }

    /**
     * 휴장일 접수분은 다음 거래일 구간에 든다. 설날 2/16(월)~2/18(수) 휴장: c = 2/19(목)의 w1 구간은 2/13(금) 다음 날인
     * 2/14 ~ 2/19라 토요일(2/14)·휴장일(2/17) 접수분을 센다. c = 2/13(T = 2/19)이면 2/13 하루라 세지 않는다. w2(c = 2/19)는
     * 2/12 다음 날 ~ 2/19.
     */
    @Test
    void 휴장일_접수_공시는_다음_거래일_구간에_넣는다() {
        FixtureData d = new FixtureData();
        d.stock("900010").stock("900020");
        d.bar("900010", LocalDate.of(2026, 2, 19), "1000", 1, "1").bar("900020", LocalDate.of(2026, 2, 19), "1000", 1, "1");
        d.disclosures = new FixtureData.Disclosures().covered("B", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 19))
                .filed("900010", "B", LocalDate.of(2026, 2, 14)).filed("900010", "B", LocalDate.of(2026, 2, 17))
                .filed("900020", "B", LocalDate.of(2026, 2, 13));
        SelectionCalculator calc = new SelectionCalculator(d);
        SelectionResult afterHoliday = calc.calculate(disclosureGate("1", "B"), LocalDate.of(2026, 2, 20));
        assertThat(eval(afterHoliday, "900010").values()).containsEntry("gate.01", "2");
        assertThat(eval(afterHoliday, "900020").values()).as("2/13은 앞 구간").containsEntry("gate.01", "0");
        SelectionResult beforeHoliday = calc.calculate(disclosureGate("1", "B"), LocalDate.of(2026, 2, 19));
        assertThat(eval(beforeHoliday, "900010").values()).containsEntry("gate.01", "0");
        assertThat(eval(beforeHoliday, "900020").values()).containsEntry("gate.01", "1");
        SelectionResult twoDays = calc.calculate(disclosureGate("2", "B"), LocalDate.of(2026, 2, 20));
        assertThat(eval(twoDays, "900010").values()).containsEntry("gate.01", "2");
        assertThat(eval(twoDays, "900020").values()).containsEntry("gate.01", "1");
    }

    @Test
    void 공시_스냅샷이_구간을_다_담지_않으면_0건으로_내지_않고_실패한다() {
        FixtureData d = newsFixture();
        SelectionProfile p = repoProfile("kr-intraday-news");
        d.disclosures = new FixtureData.Disclosures().covered("B", LocalDate.of(2026, 1, 1), C)
                .covered("I", LocalDate.of(2026, 1, 1), C.minusDays(1));
        assertThatThrownBy(() -> new SelectionCalculator(d).calculate(p, T)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("STALE_INPUT:").hasMessageContaining("유형 I");
        d.disclosures = new FixtureData.Disclosures().covered("B", C, C).covered("I", C, C);
        new SelectionCalculator(d).calculate(p, T); // w1 구간(c 하루)만 덮으면 된다
        d.disclosures = new FixtureData.Disclosures().covered("B", C.plusDays(1), C.plusDays(5)).covered("I", C, C);
        assertThatThrownBy(() -> new SelectionCalculator(d).calculate(p, T)).hasMessageStartingWith("STALE_INPUT:");
        d.disclosures = null;
        assertThatThrownBy(() -> new SelectionCalculator(d).calculate(p, T)).hasMessageStartingWith("STALE_INPUT:")
                .hasMessageContaining("공시 입력이 없습니다");
    }

    @Test
    void 프로필_유형을_스냅샷이_받지_않았으면_INPUT_TYPE_MISSING으로_실패한다() {
        FixtureData d = newsFixture();
        d.disclosures = new FixtureData.Disclosures().covered("B", LocalDate.of(2026, 1, 1), C);
        assertThatThrownBy(() -> new SelectionCalculator(d).calculate(repoProfile("kr-intraday-news"), T))
                .hasMessageStartingWith("INPUT_TYPE_MISSING:").hasMessageContaining("공시 유형 I");
    }

    @Test
    void 공시_조건이_없는_결과는_inputs에_disclosures_키가_없다() {
        FixtureData d = IntradaySelectionCalculatorTest.hotFixture();
        d.disclosures = coveredBI();
        SelectionResult r = new SelectionCalculator(d).calculate(repoProfile("kr-intraday-hot"), T);
        assertThat(r.inputs().disclosuresSnapshotId()).isNull();
        assertThat(r.canonicalJson()).doesNotContain("disclosures");
    }

    @Test
    void types_키_규칙() {
        assertThat(SelectionCriteria.parse(disclosureGate("1", "B,I")).gates().get(0).types()).containsExactly("B", "I");
        assertThat(SelectionCriteria.parse(disclosureGate("5", "B")).lookbackDays()).as("w + 1거래일").isEqualTo(6);
        assertThat(SelectionCriteria.parse(disclosureGate("1", "B")).inputKinds()).contains(Metric.Kind.DISCLOSURE);
        assertThatThrownBy(() -> SelectionCriteria.parse(disclosureGate("1", null))).hasMessageContaining("공시 유형이 필요합니다");
        assertThatThrownBy(() -> SelectionCriteria.parse(disclosureGate("1", "I,B"))).hasMessageContaining("알파벳 순");
        assertThatThrownBy(() -> SelectionCriteria.parse(disclosureGate("1", "B,B"))).hasMessageContaining("알파벳 순");
        assertThatThrownBy(() -> SelectionCriteria.parse(disclosureGate("1", "B,K"))).hasMessageContaining("공시 유형이 필요합니다");
        assertThatThrownBy(() -> SelectionCriteria.parse(disclosureGate("1", "b"))).hasMessageContaining("공시 유형이 필요합니다");
        assertThatThrownBy(() -> SelectionCriteria.parse(disclosureGate(null, "B"))).hasMessageContaining("정수가 필요합니다");

        TreeMap<String, String> other = new TreeMap<>(disclosureGate("1", "B").parameters());
        other.put("gate.01.metric", "return");
        assertThatThrownBy(() -> SelectionCriteria.parse(new SelectionProfile("x", 1, Market.KRX, TradingStyle.INTRADAY, other)))
                .hasMessageContaining("공시 유형을 받지 않습니다");

        TreeMap<String, String> score = new TreeMap<>(disclosureGate("1", "B").parameters());
        score.put("score.metric", "disclosure-count");
        score.put("score.window", "1");
        score.put("score.order", "DESC");
        assertThatThrownBy(() -> SelectionCriteria.parse(new SelectionProfile("x", 1, Market.KRX, TradingStyle.INTRADAY, score)))
                .hasMessageContaining("자격 조건(gate)에만");

        assertThatThrownBy(() -> SelectionCriteria.parse(new SelectionProfile("x", 1, Market.US, TradingStyle.INTRADAY,
                disclosureGate("1", "B").parameters()))).hasMessageContaining("KRX 선정에만");
    }
}
