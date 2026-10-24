package com.autotrade.selection.calc;

import static com.autotrade.selection.calc.SelectionCalculatorTest.eval;
import static com.autotrade.selection.calc.SelectionCalculatorTest.reasons;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.strategy.TradingStyle;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 실제 거래대금 지표 {@code turnover}(D-139, D-133 6번)의 손 계산 골든 테스트. T = 2026-03-16(월), c = 2026-03-13(금),
 * w3 구간은 c에서 끝나는 3거래일 03-11·03-12·03-13이다. 금액은 원.
 */
class TurnoverSelectionCalculatorTest {

    static final LocalDate T = LocalDate.of(2026, 3, 16);
    static final LocalDate C = LocalDate.of(2026, 3, 13);
    static final LocalDate D1 = LocalDate.of(2026, 3, 11);
    static final LocalDate D2 = LocalDate.of(2026, 3, 12);

    /** gate.01 turnover w3 ≥ 30억, 점수 turnover w3 큰 순, 2종목. */
    static SelectionProfile profile(Market market) {
        TreeMap<String, String> p = params("hypothesis", "실제 거래대금 지표 시험용", "refresh", "PRE_OPEN_DAILY",
                "max-symbols", "2",
                "gate.01.metric", "turnover", "gate.01.window", "3", "gate.01.op", "GE", "gate.01.value", "3000000000",
                "score.metric", "turnover", "score.window", "3", "score.order", "DESC");
        return new SelectionProfile("turnover-test", 1, market, TradingStyle.INTRADAY, p);
    }

    static TreeMap<String, String> params(String... kv) {
        TreeMap<String, String> p = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            p.put(kv[i], kv[i + 1]);
        }
        return p;
    }

    static FixtureData fixture() {
        FixtureData d = new FixtureData();
        for (String c : List.of("700010", "700020", "700030", "700040", "700050")) {
            d.stock(c);
        }
        d.turnover = new FixtureData.Turnover()
                // (10억 + 20억 + 60억) ÷ 3 = 30억: GE 경계 포함 → 통과, 3위라 RANK_CUTOFF
                .put("700010", D1, 1_000_000_000L).put("700010", D2, 2_000_000_000L).put("700010", C, 6_000_000_000L)
                // 거래정지 0원도 평균에 든다: (0 + 60억 + 90억) ÷ 3 = 50억 → 1위
                .put("700020", D1, 0L).put("700020", D2, 6_000_000_000L).put("700020", C, 9_000_000_000L)
                // 8,999,999,999 ÷ 3 = 2,999,999,999.666… → DECIMAL64 2999999999.666667 < 30억 → GATE_01
                .put("700030", D1, 1_000_000_000L).put("700030", D2, 2_000_000_000L).put("700030", C, 5_999_999_999L)
                // 03-12 행 없음 → NO_DATA(0으로 채우지 않음)
                .put("700040", D1, 9_000_000_000L).put("700040", C, 9_000_000_000L)
                // 40억씩 → 40억 → 2위. T 당일 1조 행은 미래 정보라 쓰지 않는다
                .put("700050", D1, 4_000_000_000L).put("700050", D2, 4_000_000_000L).put("700050", C, 4_000_000_000L)
                .put("700050", T, 1_000_000_000_000L);
        return d;
    }

    @Test
    void 실제_거래대금_평균_골든() {
        SelectionResult r = new SelectionCalculator(fixture()).calculate(profile(Market.KRX), T);

        assertThat(r.selected()).containsExactly(new SelectionResult.Selected("700020", 1),
                new SelectionResult.Selected("700050", 2));
        assertThat(reasons(r)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "700010", "RANK_CUTOFF",
                "700020", "",
                "700030", "GATE_01",
                "700040", "GATE_01_NO_DATA",
                "700050", ""));
        assertThat(eval(r, "700010").values()).isEqualTo(Map.of("gate.01", "3000000000", "score", "3000000000"));
        assertThat(eval(r, "700020").values()).isEqualTo(Map.of("gate.01", "5000000000", "score", "5000000000"));
        assertThat(eval(r, "700030").values()).isEqualTo(Map.of("gate.01", "2999999999.666667"));
        assertThat(eval(r, "700040").values()).isEmpty();
        assertThat(eval(r, "700050").values()).as("T 당일 행 무시").containsEntry("gate.01", "4000000000");

        assertThat(r.inputs().turnoverSha256()).isEqualTo("e".repeat(64));
        assertThat(r.inputs().turnoverLastDate()).isEqualTo(T);
        assertThat(r.canonicalJson()).contains("\"turnover\":{\"last_date\":\"2026-03-16\",\"sha256\":\"" + "e".repeat(64) + "\"}");
    }

    @Test
    void 근사_거래대금과_따로_계산하고_쓰지_않는_선정의_결과에는_turnover_칸이_없다() {
        FixtureData d = fixture();
        d.stock("700060");
        // 종가 10,000 × 거래량 1,000,000 = 근사 100억, 실제(시간외·대량매매 빼고 장중 체결가 낮음 가정) 10억
        d.bar("700060", C, "10000", 1_000_000, "1");
        d.turnover.put("700060", C, 1_000_000_000L);
        TreeMap<String, String> p = params("hypothesis", "근사와 비교", "refresh", "PRE_OPEN_DAILY",
                "max-symbols", "10", "gate.01.metric", "avg-turnover", "gate.01.window", "1", "gate.01.op", "GE",
                "gate.01.value", "0", "gate.02.metric", "turnover", "gate.02.window", "1", "gate.02.op", "GE",
                "gate.02.value", "0");
        SelectionResult both = new SelectionCalculator(d).calculate(
                new SelectionProfile("turnover-cmp", 1, Market.KRX, TradingStyle.INTRADAY, p), T);
        assertThat(eval(both, "700060").values()).isEqualTo(Map.of("gate.01", "10000000000", "gate.02", "1000000000"));

        p.keySet().removeIf(k -> k.startsWith("gate.02."));
        SelectionResult approxOnly = new SelectionCalculator(d).calculate(
                new SelectionProfile("turnover-cmp", 1, Market.KRX, TradingStyle.INTRADAY, p), T);
        assertThat(approxOnly.inputs().turnoverSha256()).isNull();
        assertThat(approxOnly.canonicalJson()).as("D-139 전 결과와 같은 모양(내용 해시 불변)").doesNotContain("\"turnover\"");
        d.turnover = null;
        assertThat(new SelectionCalculator(d).calculate(
                new SelectionProfile("turnover-cmp", 1, Market.KRX, TradingStyle.INTRADAY, p), T).contentHash())
                .as("거래대금 파일이 없어도 쓰지 않는 선정은 같은 결과").isEqualTo(approxOnly.contentHash());
    }

    @Test
    void 거래대금_파일이_없으면_근사로_대신하지_않고_계산_전체가_실패한다() {
        FixtureData d = fixture();
        d.turnover = null;
        assertThatThrownBy(() -> new SelectionCalculator(d).calculate(profile(Market.KRX), T))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("daily_turnover.csv가 없습니다")
                .message().doesNotStartWith("STALE_INPUT");
    }

    @Test
    void 거래대금_파일이_기준_데이터일까지_오지_않으면_STALE_INPUT() {
        FixtureData d = fixture();
        d.turnover.lastDate = D2;
        assertThatThrownBy(() -> new SelectionCalculator(d).calculate(profile(Market.KRX), T))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("STALE_INPUT:")
                .hasMessageContaining("2026-03-12").hasMessageContaining("2026-03-13");
        d.turnover.lastDate = C;
        assertThat(new SelectionCalculator(d).calculate(profile(Market.KRX), T).inputs().turnoverLastDate())
                .as("c와 같으면 계산").isEqualTo(C);
    }

    @Test
    void 실제_거래대금_지표는_KRX_선정에만_쓴다() {
        assertThatThrownBy(() -> SelectionCriteria.parse(profile(Market.US)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("KRX 선정에만");
        assertThat(SelectionCriteria.parse(profile(Market.KRX)).inputKinds())
                .containsExactlyInAnyOrder(Metric.Kind.BARS, Metric.Kind.TURNOVER);
        assertThat(SelectionCriteria.parse(profile(Market.KRX)).lookbackDays()).isEqualTo(3);
    }
}
