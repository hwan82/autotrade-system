package com.autotrade.selection.calc;

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
 * 단타 지표 {@code day-range}·{@code close-strength}·{@code volume-spike}(ADR-012 3.2)와 예시 (다) {@code kr-intraday-hot}
 * (ADR-012 3.3)의 손 계산 골든 테스트. 입력은 {@link FixtureData} 합성 데이터, 기대값은 각 주석의 손 계산이다.
 */
class IntradaySelectionCalculatorTest {

    // 선정 기준일 T 월요일, 기준 데이터일 c(직전 거래일) 금요일
    static final LocalDate T = LocalDate.of(2026, 3, 16);
    static final LocalDate C = LocalDate.of(2026, 3, 13);

    /**
     * c 앞 20거래일은 종가 {@code prior}(시가 = 고가 = 저가 = 종가, 수정 계수 {@code priorAdj})·거래량 {@code priorVol},
     * c는 시가 = 직전 종가, 고가·저가·종가 지정, 수정 계수 1·거래량 {@code cVol}.
     */
    static void day(FixtureData d, String code, String prior, String priorAdj, long priorVol, String high, String low,
            String close, long cVol) {
        List<LocalDate> w = d.days.lastN(C, 21);
        for (int i = 0; i < 20; i++) {
            d.bar(code, w.get(i), prior, priorVol, priorAdj);
        }
        d.ohlc(code, C, close, high, low, close, cVol, "1");
    }

    static FixtureData hotFixture() {
        FixtureData d = new FixtureData();
        for (String c : List.of("400010", "400020", "400030", "400040", "400050", "400060", "400070", "400080",
                "400090")) {
            d.stock(c);
        }
        // 400010: 수익률 10300/10000 − 1 = 0.03(GE 경계 포함), 급증 2,000,000 ÷ 1,000,000 = 2(GE 경계 포함),
        // 거래대금 10,300 × 2,000,000 = 206억, 변동폭 (10500 − 9900) ÷ 10000 = 0.06, 종가 강도 400 ÷ 600
        day(d, "400010", "10000", "1", 1_000_000, "10500", "9900", "10300", 2_000_000);
        // 400020: 급증 1,999,999 ÷ 1,000,000 = 1.999999 → GATE_02
        day(d, "400020", "10000", "1", 1_000_000, "10500", "9900", "10300", 1_999_999);
        // 400030: 수익률 12500/10000 − 1 = 0.25(LE 경계 포함), 거래대금 250억 → 1위
        day(d, "400030", "10000", "1", 1_000_000, "12600", "10000", "12500", 2_000_000);
        // 400040: 수익률 0.2501 → GATE_04(상한가 근접 제외)
        day(d, "400040", "10000", "1", 1_000_000, "12600", "10000", "12501", 2_000_000);
        // 400050: 수익률 0.0299 → GATE_03
        day(d, "400050", "10000", "1", 1_000_000, "10500", "9900", "10299", 2_000_000);
        // 400060: 거래대금 10,300 × 970,873 = 99억 9,999만 1,900원 < 100억 → GATE_01(급증 970,873 ÷ 400,000은 통과)
        day(d, "400060", "10000", "1", 400_000, "10500", "9900", "10300", 970_873);
        // 400070: c 종가 999원 < 1,000원 → FILTER_MIN_PRICE
        day(d, "400070", "900", "1", 1_000_000, "1000", "900", "999", 20_000_000);
        // 400080: 21거래일 중 첫날 일봉 없음 → FILTER_MIN_BAR_DAYS
        day(d, "400080", "10000", "1", 1_000_000, "10500", "9900", "10300", 2_000_000);
        d.removeBar("400080", d.days.lastN(C, 21).get(0));
        // 400090: c 전날까지 2:1 분할 전(원가 20,000, 수정 계수 0.5, 원거래량 500,000 → 수정 1,000,000).
        // 수정 급증 2,000,000 ÷ 1,000,000 = 2(원거래량이면 4), 수익률 10600 ÷ (20000 × 0.5) − 1 = 0.06, 거래대금 212억 → 2위
        day(d, "400090", "20000", "0.5", 500_000, "10800", "10000", "10600", 2_000_000);
        return d;
    }

    @Test
    void 다_전일_관심_종목_골든() {
        SelectionProfile p = repoProfile("kr-intraday-hot");
        assertThat(p.style()).isEqualTo(TradingStyle.INTRADAY);
        assertThat(p.refresh()).isEqualTo(SelectionRefresh.PRE_OPEN_DAILY);

        SelectionResult r = new SelectionCalculator(hotFixture()).calculate(p, T);

        assertThat(r.applyFrom()).isEqualTo(T);
        assertThat(r.applyTo()).as("장전 매일 갱신은 T 하루").isEqualTo(T);
        assertThat(r.selected()).as("전일 거래대금 큰 순").containsExactly(new SelectionResult.Selected("400030", 1),
                new SelectionResult.Selected("400090", 2), new SelectionResult.Selected("400010", 3));
        assertThat(reasons(r)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "400010", "", "400030", "", "400090", "",
                "400020", "GATE_02",
                "400040", "GATE_04",
                "400050", "GATE_03",
                "400060", "GATE_01",
                "400070", "FILTER_MIN_PRICE",
                "400080", "FILTER_MIN_BAR_DAYS"));
        assertThat(eval(r, "400010").values()).isEqualTo(Map.of("gate.01", "20600000000", "gate.02", "2",
                "gate.03", "0.03", "gate.04", "0.03", "score", "20600000000"));
        assertThat(eval(r, "400020").values()).containsEntry("gate.02", "1.999999");
        assertThat(eval(r, "400030").values()).containsEntry("gate.04", "0.25");
        assertThat(eval(r, "400040").values()).containsEntry("gate.04", "0.2501");
        assertThat(eval(r, "400050").values()).containsEntry("gate.03", "0.0299");
        assertThat(eval(r, "400060").values()).containsEntry("gate.01", "9999991900");
        assertThat(eval(r, "400090").values()).as("수정 거래량·수정 종가")
                .containsEntry("gate.02", "2").containsEntry("gate.03", "0.06").containsEntry("gate.01", "21200000000");
        assertThat(r.inputs().sharesSnapshotId()).as("일봉만 쓰는 선정").isNull();
        assertThat(r.inputs().financialsSnapshotId()).isNull();
    }

    @Test
    void 다_선정은_T_당일_이후_일봉을_쓰지_않는다() {
        FixtureData d = hotFixture();
        SelectionProfile p = repoProfile("kr-intraday-hot");
        String before = new SelectionCalculator(d).calculate(p, T).contentHash();
        d.ohlc("400020", T, "10300", "20000", "10000", "19000", 99_000_000, "1")
                .ohlc("400050", T.plusDays(1), "1", "1", "1", "1", 1, "1");
        assertThat(new SelectionCalculator(d).calculate(p, T).contentHash()).isEqualTo(before);
        d.ohlc("400020", C, "10300", "10500", "9900", "10300", 2_000_000, "1");
        assertThat(new SelectionCalculator(d).calculate(p, T).contentHash()).as("c 값은 결과를 바꿈").isNotEqualTo(before);
    }

    // ---------------------------------------------------------------- 지표별

    static SelectionProfile intraday(String metric, String window, String op, String value) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("hypothesis", "단타 지표 시험용");
        p.put("refresh", "PRE_OPEN_DAILY");
        p.put("max-symbols", "50");
        p.put("gate.01.metric", metric);
        if (window != null) {
            p.put("gate.01.window", window);
        }
        p.put("gate.01.op", op);
        p.put("gate.01.value", value);
        return new SelectionProfile("intraday-test", 1, Market.KRX, TradingStyle.INTRADAY, p);
    }

    /**
     * 변동폭 = (c 수정 고가 − c 수정 저가) ÷ c−1 수정 종가. c−1에 수정 계수 0.5(분할 전 원가 20,000 → 수정 10,000)면
     * (10500 − 9900) ÷ 10000 = 0.06(원가로 나누면 0.03). 수정 계수가 c−1·c 모두 1이 아니어도 정확히 0.1은 0.1(GE 경계 포함).
     */
    @Test
    void 변동폭은_수정_가격으로_계산하고_경계에_흔들리지_않는다() {
        FixtureData d = new FixtureData();
        d.stock("500010").stock("500020").stock("500030").stock("500040");
        d.bar("500010", d.days.previous(C), "10000", 1000, "1").ohlc("500010", C, "10000", "10500", "9900", "10300", 1000, "1");
        d.bar("500020", d.days.previous(C), "20000", 1000, "0.5").ohlc("500020", C, "10000", "10500", "9900", "10300", 1000, "1");
        String f = "0.9876543210123";
        d.bar("500030", d.days.previous(C), "10000", 1000, f).ohlc("500030", C, "10000", "10500", "9500", "10000", 1000, f);
        d.ohlc("500040", C, "10000", "10500", "9900", "10300", 1000, "1"); // c−1 일봉 없음
        SelectionResult r = new SelectionCalculator(d).calculate(intraday("day-range", null, "GE", "0.1"), T);
        assertThat(eval(r, "500010").values()).containsEntry("gate.01", "0.06");
        assertThat(eval(r, "500020").values()).as("분할 낀 날").containsEntry("gate.01", "0.06");
        assertThat(eval(r, "500030").values()).containsEntry("gate.01", "0.1");
        assertThat(eval(r, "500030").passed()).isTrue();
        assertThat(eval(r, "500040").reasons()).containsExactly("GATE_01_NO_DATA");
    }

    /** 종가 강도 = (종가 − 저가) ÷ (고가 − 저가). 고가 마감 1, 저가 마감 0, 고가 = 저가(상한가 고정 등)는 정의 안 됨. */
    @Test
    void 종가_강도는_고가_마감이_1이고_고가와_저가가_같으면_결측이다() {
        FixtureData d = new FixtureData();
        d.stock("600010").stock("600020").stock("600030").stock("600040").stock("600050");
        d.ohlc("600010", C, "10000", "10600", "10000", "10600", 1000, "0.5"); // 고가 마감 → 1
        d.ohlc("600020", C, "10000", "10600", "10000", "10000", 1000, "1"); // 저가 마감 → 0
        d.ohlc("600030", C, "13000", "13000", "13000", "13000", 1000, "1"); // 상한가 고정
        d.ohlc("600040", C, "10000", "10500", "9900", "10300", 1000, "1"); // 400 ÷ 600
        d.bar("600050", d.days.previous(C), "10000", 1000, "1"); // c 일봉 없음
        SelectionResult r = new SelectionCalculator(d).calculate(intraday("close-strength", null, "GE", "1"), T);
        assertThat(eval(r, "600010").values()).containsEntry("gate.01", "1");
        assertThat(eval(r, "600010").passed()).as("GE 1 경계 포함").isTrue();
        assertThat(eval(r, "600020").values()).containsEntry("gate.01", "0");
        assertThat(eval(r, "600030").reasons()).containsExactly("GATE_01_NO_DATA");
        assertThat(eval(r, "600040").values()).containsEntry("gate.01", "0.6666666666666667");
        assertThat(eval(r, "600050").reasons()).containsExactly("GATE_01_NO_DATA");
    }

    /**
     * 거래량 급증 = c 수정 거래량 ÷ (앞 w거래일 수정 거래량 평균). 기존 volume-ratio(w일 합 ÷ 앞 w일 합)와 달리 c 하루를 본다.
     * w = 3, 앞 3일 수정 거래량 100·200·300(평균 200), c 500 → 2.5. 수정 계수가 1이 아니어도 정확히 2배는 2.
     * 거래정지일(거래량 0 일봉)이 앞 구간이나 c에 하루라도 있으면 결측이다. 700060을 평균에 넣으면 (100 + 0 + 100) ÷ 3 대비
     * 500 → 7.5배로 부풀어 통과하지만, 결측으로 탈락해야 한다(PR #193 strategy-researcher M1).
     */
    @Test
    void 거래량_급증은_c_하루를_앞_구간_평균과_비교한다() {
        FixtureData d = new FixtureData();
        d.stock("700010").stock("700020").stock("700030").stock("700040").stock("700050").stock("700060")
                .stock("700070");
        List<LocalDate> w = d.days.lastN(C, 4);
        long[] vols = {100, 200, 300};
        for (int i = 0; i < 3; i++) {
            d.bar("700010", w.get(i), "10000", vols[i], "1");
            d.bar("700020", w.get(i), "10000", 1_000_003L, "0.123456789");
            d.bar("700030", w.get(i), "10000", 0, "1");
            d.bar("700040", w.get(i), "10000", 100, "1");
            d.bar("700050", w.get(i), "10000", 100, "1");
            d.bar("700060", w.get(i), "10000", i == 1 ? 0 : 100, "1"); // 앞 구간 하루 거래정지
            d.bar("700070", w.get(i), "10000", 100, "1");
        }
        d.bar("700010", C, "10000", 500, "1");
        d.bar("700020", C, "10000", 2_000_006L, "0.123456789");
        d.bar("700030", C, "10000", 500, "1"); // 앞 구간 합 0
        d.bar("700040", C, "10000", 500, "1");
        d.removeBar("700040", w.get(0)); // 앞 구간 하루 결측
        d.bar("700060", C, "10000", 500, "1");
        d.bar("700070", C, "10000", 0, "1"); // c 거래정지
        // 700050: c 일봉 없음
        SelectionResult r = new SelectionCalculator(d).calculate(intraday("volume-spike", "3", "GE", "2"), T);
        assertThat(eval(r, "700010").values()).containsEntry("gate.01", "2.5");
        assertThat(eval(r, "700020").values()).containsEntry("gate.01", "2");
        assertThat(eval(r, "700020").passed()).isTrue();
        assertThat(eval(r, "700030").reasons()).containsExactly("GATE_01_NO_DATA");
        assertThat(eval(r, "700040").reasons()).containsExactly("GATE_01_NO_DATA");
        assertThat(eval(r, "700050").reasons()).containsExactly("GATE_01_NO_DATA");
        assertThat(eval(r, "700060").reasons()).as("앞 구간 거래정지일").containsExactly("GATE_01_NO_DATA");
        assertThat(eval(r, "700070").reasons()).as("c 거래정지").containsExactly("GATE_01_NO_DATA");
    }

    @Test
    void 단타_지표의_기간_키와_필요한_일봉_일수() {
        assertThat(SelectionCriteria.parse(intraday("volume-spike", "20", "GE", "2")).lookbackDays())
                .as("c 포함 21거래일").isEqualTo(21);
        assertThat(SelectionCriteria.parse(intraday("day-range", null, "GE", "0.05")).lookbackDays()).isEqualTo(2);
        assertThat(SelectionCriteria.parse(intraday("close-strength", null, "GE", "0.8")).lookbackDays()).isEqualTo(1);
        assertThatThrownBy(() -> SelectionCriteria.parse(intraday("day-range", "5", "GE", "0.05")))
                .hasMessageContaining("기간을 받지 않습니다");
        assertThatThrownBy(() -> SelectionCriteria.parse(intraday("close-strength", "5", "GE", "0.8")))
                .hasMessageContaining("기간을 받지 않습니다");
        assertThatThrownBy(() -> SelectionCriteria.parse(intraday("volume-spike", null, "GE", "2")))
                .hasMessageContaining("정수가 필요합니다");
    }
}
