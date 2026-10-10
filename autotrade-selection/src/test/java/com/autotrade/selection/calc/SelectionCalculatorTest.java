package com.autotrade.selection.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.rules.InstrumentKind;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionProfileYaml;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.strategy.TradingStyle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 선정 예시 (가)(나)의 손 계산 골든 테스트(STR-16 인수 조건)와 시점 규칙(MD-10) 테스트. 입력은 {@link FixtureData}의 작은
 * 합성 데이터이고, 기대값은 각 테스트 주석의 손 계산이다. 프로필은 저장소의 {@code selections/} 파일을 그대로 읽는다.
 */
class SelectionCalculatorTest {

    // (나) 선정 기준일 월요일, 기준 데이터일(직전 거래일) 금요일
    static final LocalDate T_VOL = LocalDate.of(2026, 3, 16);
    static final LocalDate C_VOL = LocalDate.of(2026, 3, 13);
    // (가) 주간 갱신: 4/6 주와 4/13 주의 첫 거래일
    static final LocalDate T1 = LocalDate.of(2026, 4, 6);
    static final LocalDate T2 = LocalDate.of(2026, 4, 13);

    static SelectionProfile repoProfile(String id) {
        Path f = Path.of("../selections").resolve(id).resolve("v1.yaml");
        try {
            return SelectionProfileYaml.parse(Files.readString(f, StandardCharsets.UTF_8), f.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- (나) 거래량 급증·가격 정체

    /**
     * 40거래일(앞 20 + 최근 20). 앞 구간 종가 {@code base}(수정 계수 {@code priorAdj}), 최근 구간 종가 base·마지막 날 {@code last}.
     * 수익률 = last ÷ (앞 구간 마지막 날 수정 종가) − 1.
     */
    static void surge(FixtureData d, String code, long priorVol, long recentVol, String base, String last, String priorAdj,
            String recentBase) {
        List<LocalDate> w = d.days.lastN(C_VOL, 40);
        for (int i = 0; i < 40; i++) {
            if (i < 20) {
                d.bar(code, w.get(i), base, priorVol, priorAdj);
            } else {
                d.bar(code, w.get(i), i == 39 ? last : recentBase, recentVol, "1");
            }
        }
    }

    static FixtureData volumeFixture() {
        FixtureData d = new FixtureData();
        d.stock("000010").stock("000020").stock("000030").stock("000040").stock("000070");
        d.listed("000050", InstrumentKind.STOCK, FixtureData.FROM, LocalDate.of(2026, 3, 20)); // 기준일 뒤 상장폐지
        d.listed("000060", InstrumentKind.STOCK, FixtureData.FROM, LocalDate.of(2026, 3, 12)); // 기준 데이터일 전 폐지
        d.listed("000080", InstrumentKind.ETF, FixtureData.FROM, null);
        surge(d, "000010", 1000, 3000, "10000", "10299", "1", "10000"); // 비율 3(경계 포함), 수익률 2.99%
        surge(d, "000020", 1000, 3000, "10000", "10300", "1", "10000"); // 수익률 정확히 3% → 탈락(미만 조건)
        surge(d, "000030", 1000, 2999, "10000", "10000", "1", "10000"); // 비율 2.999
        // 2:1 분할이 창 안에: 앞 구간 원거래량 1000 × (1 ÷ 0.5) = 수정 2000, 최근 4000 → 수정 비율 2(원거래량이면 4)
        surge(d, "000040", 1000, 4000, "20000", "10100", "0.5", "10000");
        surge(d, "000050", 1000, 4000, "10000", "9500", "1", "10000"); // 비율 4, 수익률 −5%(하락도 포함)
        surge(d, "000060", 1000, 4000, "10000", "9500", "1", "10000");
        surge(d, "000070", 1000, 3000, "10000", "10299", "1", "10000");
        d.removeBar("000070", d.days.lastN(C_VOL, 40).get(5)); // 앞 구간 하루 결측 → 거래량 비율 결측
        surge(d, "000080", 1000, 4000, "10000", "9500", "1", "10000");
        return d;
    }

    @Test
    void 나_거래량_급증_가격_정체_골든() {
        SelectionResult r = new SelectionCalculator(volumeFixture()).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL);

        assertThat(r.applyFrom()).isEqualTo(T_VOL);
        assertThat(r.applyTo()).as("매일 갱신은 하루").isEqualTo(T_VOL);
        assertThat(r.selected()).containsExactly(new SelectionResult.Selected("000050", 1),
                new SelectionResult.Selected("000010", 2));
        Map<String, String> reasons = reasons(r);
        assertThat(reasons).containsExactlyInAnyOrderEntriesOf(Map.of(
                "000010", "",
                "000020", "GATE_02",
                "000030", "GATE_01",
                "000040", "GATE_01",
                "000050", "",
                "000070", "GATE_01_NO_DATA",
                "000080", "FILTER_KIND"));
        assertThat(reasons).as("기준 데이터일 전에 폐지된 종목은 그날 유니버스 밖").doesNotContainKey("000060");
        assertThat(eval(r, "000010").values()).isEqualTo(Map.of("gate.01", "3", "gate.02", "0.0299", "score", "3"));
        assertThat(eval(r, "000020").values()).containsEntry("gate.02", "0.03");
        assertThat(eval(r, "000030").values()).containsEntry("gate.01", "2.999");
        assertThat(eval(r, "000040").values()).as("수정 거래량·수정 종가").isEqualTo(Map.of("gate.01", "2", "gate.02", "0.01"));
        assertThat(eval(r, "000050").values()).containsEntry("gate.02", "-0.05").containsEntry("score", "4");
        assertThat(eval(r, "000070").values()).as("수익률은 21거래일만 보므로 계산됨").isEqualTo(Map.of("gate.02", "0.0299"));
        assertThat(r.inputs().barsLastTradingDate()).as("스냅샷 수록 기준일을 옮김").isEqualTo(FixtureData.TO);
        assertThat(r.inputs().sharesSnapshotId()).as("시가총액 조건이 없으면 상장주식수 입력을 적지 않음").isNull();
        assertThat(r.inputs().financialsSnapshotId()).isNull();
        assertThat(r.restatementFallbackCount()).isZero();
    }

    @Test
    void 기준일_당일_이후_일봉은_쓰지_않는다() {
        FixtureData d = volumeFixture();
        String before = new SelectionCalculator(d).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL).contentHash();
        d.bar("000030", T_VOL, "99999", 999_999, "1").bar("000030", T_VOL.plusDays(1), "1", 1, "1");
        assertThat(new SelectionCalculator(d).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL).contentHash())
                .isEqualTo(before);
        d.bar("000030", C_VOL, "10000", 999_999, "1");
        assertThat(new SelectionCalculator(d).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL).contentHash())
                .as("기준 데이터일 값은 결과를 바꿈").isNotEqualTo(before);
    }

    @Test
    void 같은_입력이면_결과와_내용_해시가_같다() {
        SelectionProfile p = repoProfile("kr-vol-surge-quiet");
        SelectionResult a = new SelectionCalculator(volumeFixture()).calculate(p, T_VOL);
        SelectionResult b = new SelectionCalculator(volumeFixture()).calculate(p, T_VOL);
        assertThat(a).isEqualTo(b);
        assertThat(a.canonicalJson()).isEqualTo(b.canonicalJson());
        assertThat(a.contentHash()).isEqualTo(b.contentHash()).matches("[0-9a-f]{64}");
        assertThat(a.selection().hash()).isEqualTo(p.selectionHash());
    }

    @Test
    void 순위_상한을_넘는_통과_종목은_RANK_CUTOFF로_남는다() {
        TreeMap<String, String> params = new TreeMap<>(repoProfile("kr-vol-surge-quiet").parameters());
        params.put("max-symbols", "1");
        SelectionProfile one = new SelectionProfile("vol-one", 1, Market.KRX, TradingStyle.SWING, params);
        SelectionResult r = new SelectionCalculator(volumeFixture()).calculate(one, T_VOL);
        assertThat(r.selected()).containsExactly(new SelectionResult.Selected("000050", 1));
        assertThat(eval(r, "000010").reasons()).containsExactly("RANK_CUTOFF");
        assertThat(eval(r, "000010").values()).containsEntry("score", "3");
    }

    @Test
    void 점수가_없으면_종목_코드_순으로_자른다() {
        TreeMap<String, String> params = new TreeMap<>(repoProfile("kr-vol-surge-quiet").parameters());
        params.keySet().removeIf(k -> k.startsWith("score."));
        params.put("max-symbols", "1");
        SelectionProfile p = new SelectionProfile("vol-noscore", 1, Market.KRX, TradingStyle.SWING, params);
        SelectionResult r = new SelectionCalculator(volumeFixture()).calculate(p, T_VOL);
        assertThat(r.selected()).containsExactly(new SelectionResult.Selected("000010", 1));
        assertThat(eval(r, "000050").reasons()).containsExactly("RANK_CUTOFF");
    }

    // ---------------------------------------------------------------- (가) 재무 4조건

    static final long[] BASE = {1000, 100, 1000, 500, 500}; // 영업이익률 10%, 자산 1000
    static final long[] GOOD = {2000, 300, 1300, 500, 1000}; // 15% → +50%, 자산 +30%, 부채비율 0.5

    static FixtureData financialFixture() {
        FixtureData d = new FixtureData();
        LocalDate end = LocalDate.of(2026, 4, 17);
        LocalDate r22 = LocalDate.of(2023, 3, 20);
        LocalDate r25 = LocalDate.of(2026, 3, 20);
        for (String c : List.of("100010", "100020", "100030", "100040", "100050", "100060", "100070", "100080")) {
            d.stock(c);
            if (!c.equals("100010") && !c.equals("100080")) {
                d.flat(c, end, 20, "50000", 1000).shares(c, FixtureData.FROM, 10_000_000); // 시가총액 5,000억
            }
        }
        // 100010: 모든 조건이 정확히 경계. 4/3에 2:1 분할(종가 60,000 → 30,000, 주식수 500만 → 1,000만)
        d.flat("100010", end, 20, "30000", 1000).bar("100010", LocalDate.of(2026, 4, 2), "60000", 1000, "0.5")
                .shares("100010", FixtureData.FROM, 5_000_000).shares("100010", LocalDate.of(2026, 4, 3), 10_000_000)
                .annual("100010", 2022, "r10-22", r22, false, BASE)
                .annual("100010", 2025, "r10-25", r25, false, new long[] {2000, 300, 1300, 1500, 1000});
        // 100020: 3년 전 적자 → 영업이익률 증가율 정의 안 됨
        d.annual("100020", 2022, "r20-22", r22, false, new long[] {1000, -10, 1000, 500, 500})
                .annual("100020", 2025, "r20-25", r25, false, GOOD);
        // 100030: 자본잠식
        d.annual("100030", 2022, "r30-22", r22, false, BASE)
                .annual("100030", 2025, "r30-25", r25, false, new long[] {2000, 300, 1300, 1400, -100});
        // 100040: FY2025 접수일이 4/6(T1 당일) → T1에는 FY2024 vs FY2021(증가 0), T2부터 FY2025 vs FY2022
        d.annual("100040", 2021, "r40-21", LocalDate.of(2022, 3, 20), false, BASE)
                .annual("100040", 2022, "r40-22", r22, false, BASE)
                .annual("100040", 2024, "r40-24", LocalDate.of(2025, 3, 20), false, BASE)
                .annual("100040", 2025, "r40-25", T1, false, GOOD);
        // 100050: 정정 기본 규칙(정정 전 값 있음). 4/8 정정으로 영업이익 300 → 240(12%, +20%)
        d.annual("100050", 2022, "r50-22", r22, false, BASE)
                .annual("100050", 2025, "r50-25", r25, false, GOOD)
                .annual("100050", 2025, "r50-25c", LocalDate.of(2026, 4, 8), true, new long[] {2000, 240, 1300, 500, 1000});
        // 100060: 정정 대체 규칙(원 공시 값 모름). T1에는 FY2024 vs FY2021(편향 표시), T2부터 정정 값(FY2025 vs FY2022)
        d.annual("100060", 2021, "r60-21", LocalDate.of(2022, 3, 20), false, BASE)
                .annual("100060", 2022, "r60-22", r22, false, BASE)
                .annual("100060", 2024, "r60-24", LocalDate.of(2025, 3, 20), false, GOOD)
                .annual("100060", 2025, "r60-25", r25, false, null)
                .annual("100060", 2025, "r60-25c", LocalDate.of(2026, 4, 8), true, BASE);
        // 100070: 재무 없음
        // 100080: 100010과 같은 재무, 종가 29,999 → 시가총액 2,999.9억
        d.flat("100080", end, 20, "29999", 1000).shares("100080", FixtureData.FROM, 10_000_000)
                .annual("100080", 2022, "r80-22", r22, false, BASE)
                .annual("100080", 2025, "r80-25", r25, false, GOOD);
        return d;
    }

    @Test
    void 가_재무_4조건_골든_T1() {
        SelectionResult r = new SelectionCalculator(financialFixture()).calculate(repoProfile("kr-fin-quality-growth"), T1);

        assertThat(r.applyTo()).as("주간 갱신은 그 ISO 주 마지막 거래일까지").isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(r.selected()).extracting(SelectionResult.Selected::code)
                .as("점수(영업이익률 증가율) 모두 0.5 → 종목 코드 순").containsExactly("100010", "100050", "100060");
        assertThat(reasons(r)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "100010", "", "100050", "", "100060", "",
                "100020", "GATE_01_DEFICIT",
                "100030", "GATE_04_CAPITAL_IMPAIRED",
                "100040", "GATE_01,GATE_02",
                "100070", "GATE_01_NO_DATA,GATE_02_NO_DATA,GATE_04_NO_DATA",
                "100080", "GATE_03"));
        // 100010: (300/2000)/(100/1000) − 1 = 0.5, 1300/1000 − 1 = 0.3, 30,000 × 1,000만 = 3,000억(분할 뒤 종가 × 분할 뒤
        // 주식수, 직전 거래일 4/3 기준), 1500/1000 = 1.5
        assertThat(eval(r, "100010").values()).isEqualTo(Map.of("gate.01", "0.5", "gate.02", "0.3",
                "gate.03", "300000000000", "gate.04", "1.5", "score", "0.5"));
        assertThat(eval(r, "100040").values()).as("T1 당일 접수 FY2025는 아직 못 씀")
                .containsEntry("gate.01", "0").containsEntry("gate.02", "0");
        assertThat(eval(r, "100050").restatementFallback()).isFalse();
        assertThat(eval(r, "100060").restatementFallback()).as("정정 전 값이 없어 직전 기간 값 사용").isTrue();
        assertThat(eval(r, "100080").values()).containsEntry("gate.03", "299990000000");
        assertThat(r.restatementFallbackCount()).isEqualTo(1);
        assertThat(r.inputs().sharesAsOfDate()).isEqualTo(FixtureData.TO);
        assertThat(r.inputs().financialsSnapshotId()).isEqualTo("dart-test");
    }

    @Test
    void 가_재무_4조건_T2_접수일_다음_거래일부터_새_공시와_정정을_쓴다() {
        SelectionResult r = new SelectionCalculator(financialFixture()).calculate(repoProfile("kr-fin-quality-growth"), T2);

        assertThat(r.selected()).extracting(SelectionResult.Selected::code).containsExactly("100010", "100040");
        assertThat(eval(r, "100050").reasons()).as("기본 규칙: 정정 접수일(4/8) 다음 거래일부터 정정 값").containsExactly("GATE_01");
        assertThat(eval(r, "100050").values()).containsEntry("gate.01", "0.2");
        assertThat(eval(r, "100060").reasons()).containsExactly("GATE_01", "GATE_02");
        assertThat(eval(r, "100060").restatementFallback()).as("정정 값이 생긴 뒤에는 대체 규칙 아님").isFalse();
        assertThat(r.restatementFallbackCount()).isZero();
    }

    @Test
    void 조건이_쓰는_입력이_없으면_계산하지_않는다() {
        FixtureData noFin = financialFixture();
        noFin.withFinancials = false;
        assertThatThrownBy(() -> new SelectionCalculator(noFin).calculate(repoProfile("kr-fin-quality-growth"), T1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("재무 입력이 없습니다");
        FixtureData noShares = financialFixture();
        noShares.withShares = false;
        assertThatThrownBy(() -> new SelectionCalculator(noShares).calculate(repoProfile("kr-fin-quality-growth"), T1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("상장주식수 입력이 없습니다");
        // 시가총액·재무 조건이 없는 프로필은 그 입력 없이도 계산한다
        FixtureData vol = volumeFixture();
        vol.withShares = false;
        vol.withFinancials = false;
        assertThat(new SelectionCalculator(vol).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL).selected()).hasSize(2);
    }

    /** 입력 스냅샷이 기준 데이터일(직전 거래일)까지 오지 않으면 조용히 0종목을 내지 않고 실패한다(ADR-007 2.6 STALE_INPUT). */
    @Test
    void 입력_기준일이_기준_데이터일보다_이르면_계산하지_않는다() {
        FixtureData bars = volumeFixture();
        bars.lastTradingDate = LocalDate.of(2026, 3, 12);
        assertThatThrownBy(() -> new SelectionCalculator(bars).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2026-03-12").hasMessageContaining("2026-03-13");
        bars.lastTradingDate = C_VOL;
        assertThat(new SelectionCalculator(bars).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL)
                .inputs().barsLastTradingDate()).as("경계: 기준 데이터일과 같으면 계산").isEqualTo(C_VOL);

        FixtureData shares = financialFixture();
        shares.sharesAsOf = LocalDate.of(2026, 4, 2);
        assertThatThrownBy(() -> new SelectionCalculator(shares).calculate(repoProfile("kr-fin-quality-growth"), T1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("상장주식수");

        FixtureData fin = financialFixture();
        fin.financialsCollected = LocalDate.of(2026, 4, 2);
        assertThatThrownBy(() -> new SelectionCalculator(fin).calculate(repoProfile("kr-fin-quality-growth"), T1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("재무");
        fin.financialsCollected = null;
        assertThatThrownBy(() -> new SelectionCalculator(fin).calculate(repoProfile("kr-fin-quality-growth"), T1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("수집 시각");
        fin.financialsCollected = LocalDate.of(2026, 4, 3);
        assertThat(new SelectionCalculator(fin).calculate(repoProfile("kr-fin-quality-growth"), T1).selected())
                .as("경계: 기준 데이터일에 수집했으면 계산").isNotEmpty();
    }

    /**
     * 경계 포함(GE 3.0)은 수정 계수가 1이 아닐 때도 지켜진다. 앞·최근 구간 원거래량이 1:3이고 수정 계수가 같으면 수정 거래량
     * 비율은 정확히 3이다. 일별 나눗셈의 반올림 오차가 쌓여 2.999…가 되면 안 된다(PR #45 architect F2).
     */
    @Test
    void 거래량_비율_경계는_수정_계수가_1이_아니어도_정확하다() {
        String[] factors = {"0.3", "0.7", "0.9", "1.3", "1.7", "2.3", "3", "6", "7", "9", "11", "13", "0.17", "0.123456789"};
        FixtureData d = new FixtureData();
        List<LocalDate> w = d.days.lastN(C_VOL, 40);
        for (int k = 0; k < factors.length; k++) {
            String code = String.format("2%05d", k);
            d.stock(code);
            for (int i = 0; i < 40; i++) {
                d.bar(code, w.get(i), "10000", i < 20 ? 1_000_003L + k : 3L * (1_000_003L + k), factors[k]);
            }
        }
        SelectionResult r = new SelectionCalculator(d).calculate(repoProfile("kr-vol-surge-quiet"), T_VOL);
        assertThat(r.evaluations()).hasSize(factors.length).allSatisfy(e -> {
            assertThat(e.passed()).as(e.code()).isTrue();
            assertThat(e.values()).as(e.code()).containsEntry("gate.01", "3");
        });
    }

    // ---------------------------------------------------------------- 적용 범위

    @Test
    void 주간_갱신은_그_주_첫_거래일이_기준일이고_휴장일을_건너뛴다() {
        FixtureData d = new FixtureData();
        SelectionCalculator calc = new SelectionCalculator(d);
        SelectionProfile weekly = repoProfile("kr-fin-quality-growth");
        assertThat(calc.applyFromFor(weekly, LocalDate.of(2026, 4, 8))).isEqualTo(T1);
        assertThat(calc.applyFromFor(weekly, LocalDate.of(2026, 2, 19))).as("월~수 설날").isEqualTo(LocalDate.of(2026, 2, 19));
        assertThat(calc.applyToFor(weekly, LocalDate.of(2026, 2, 19))).isEqualTo(LocalDate.of(2026, 2, 20));
        assertThat(calc.applyFromFor(weekly, LocalDate.of(2026, 3, 5))).as("월요일 휴장").isEqualTo(LocalDate.of(2026, 3, 3));
        assertThat(calc.applyFromFor(repoProfile("kr-vol-surge-quiet"), LocalDate.of(2026, 4, 8)))
                .isEqualTo(LocalDate.of(2026, 4, 8));
        assertThatThrownBy(() -> calc.calculate(weekly, LocalDate.of(2026, 4, 8))).hasMessageContaining("첫 거래일");
        assertThatThrownBy(() -> calc.calculate(weekly, LocalDate.of(2026, 4, 11))).hasMessageContaining("거래일이 아닙니다");
        assertThatThrownBy(() -> calc.calculate(weekly, LocalDate.of(2027, 1, 4))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 시장이_다른_데이터는_거부한다() {
        TreeMap<String, String> params = new TreeMap<>(repoProfile("kr-vol-surge-quiet").parameters());
        SelectionProfile us = new SelectionProfile("us-vol", 1, Market.US, TradingStyle.SWING, params);
        assertThatThrownBy(() -> new SelectionCalculator(volumeFixture()).calculate(us, T_VOL)).hasMessageContaining("시장");
    }

    static SelectionResult.Evaluation eval(SelectionResult r, String code) {
        return r.evaluations().stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
    }

    static Map<String, String> reasons(SelectionResult r) {
        return r.evaluations().stream().collect(Collectors.toMap(SelectionResult.Evaluation::code,
                e -> String.join(",", e.reasons())));
    }
}
