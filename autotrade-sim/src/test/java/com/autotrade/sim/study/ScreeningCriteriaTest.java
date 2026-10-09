package com.autotrade.sim.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.autotrade.domain.Money;
import com.autotrade.domain.market.Market;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradingStyle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 선발 기준 C1~C10 판정을 손으로 계산한 기대값으로 확인한다(경계값, 판단 불가 경로). 실행기 없이 결과 레코드를 직접 만든다.
 * 시드 10,000,000원, 학습 24일·검증 16일, 기본 지연 0.5초·판정 지연 2초, 기본 임계값({@link ScreeningThresholds#defaults()}).
 */
class ScreeningCriteriaTest {

    private static final BigDecimal SEED = new BigDecimal("10000000");
    private static final Duration BASE = Duration.ofMillis(500);
    private static final Duration STRESS = Duration.ofSeconds(2);
    private static final String BH = "bh";
    private static final String RND = "rnd";
    private static final String CAND = "cand";

    // ---------- C1 ----------

    @Test
    void C1_기준선과_같은_수익률은_초과가_아니다() {
        Fixture f = new Fixture();
        f.val.put(BH, run(BH, metrics("0.05", "0.05", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C1")).isEqualTo(Check.Outcome.FAIL);
        f.val.put(BH, run(BH, metrics("0.0499", "0.05", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C1")).isEqualTo(Check.Outcome.PASS);
        f.val.put(BH, new RunSummary(BH, "h-bh", RunStatus.FAILED, "실패", null, List.of(), 0, new TreeMap<>()));
        assertThat(f.check("C1")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    // ---------- C2 ----------

    @Test
    void C2_PF는_하한_1_3과_같으면_통과_아래면_미달() {
        Fixture f = new Fixture();
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", "2", "0.5", "1.3", 40)));
        assertThat(f.check("C2")).isEqualTo(Check.Outcome.PASS);
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", "2", "0.5", "1.29", 40)));
        assertThat(f.check("C2")).isEqualTo(Check.Outcome.FAIL);
    }

    @Test
    void C2_PF가_없으면_손실_왕복_여부로_왕복이_없으면_판단_불가() {
        Fixture f = new Fixture();
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", "2", "1.0", null, 40)));
        assertThat(f.check("C2")).isEqualTo(Check.Outcome.PASS); // 손실 왕복 없음 = 무한대
        f.val.put(CAND, f.candVal(metrics("0", "0", "0", "0", null, 40)));
        assertThat(f.check("C2")).isEqualTo(Check.Outcome.FAIL); // 손익 0
        f.val.put(CAND, f.candVal(metrics("0", "0", null, null, null, 0)));
        assertThat(f.check("C2")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    // ---------- C3·C4·C5 ----------

    @Test
    void C3_MDD는_20퍼센트와_같으면_통과_넘으면_미달() {
        Fixture f = new Fixture();
        f.val.put(CAND, f.candVal(metrics("0.05", "0.20", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C3")).isEqualTo(Check.Outcome.PASS);
        f.val.put(CAND, f.candVal(metrics("0.05", "0.2001", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C3")).isEqualTo(Check.Outcome.FAIL);
    }

    @Test
    void C4_샤프_1_0_경계와_계산_불가() {
        Fixture f = new Fixture();
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", "1.0", "0.5", "2.0", 40)));
        assertThat(f.check("C4")).isEqualTo(Check.Outcome.PASS);
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", "0.99", "0.5", "2.0", 40)));
        assertThat(f.check("C4")).isEqualTo(Check.Outcome.FAIL);
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", null, "0.5", "2.0", 40)));
        assertThat(f.check("C4")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    @Test
    void C5_왕복_30건_경계() {
        Fixture f = new Fixture();
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", "2", "0.5", "2.0", 30)));
        assertThat(f.check("C5")).isEqualTo(Check.Outcome.PASS);
        f.val.put(CAND, f.candVal(metrics("0.05", "0.05", "2", "0.5", "2.0", 29)));
        assertThat(f.check("C5")).isEqualTo(Check.Outcome.FAIL);
    }

    // ---------- C6 ----------

    @Test
    void C6_검증_하루_수익률이_학습의_정확히_60퍼센트면_통과() {
        Fixture f = new Fixture();
        // 학습 0.024 / 24일 = 0.001, 검증 0.0096 / 16일 = 0.0006 = 0.6 × 0.001
        f.train.put(CAND, run(CAND, metrics("0.024", "0.05", "2", "0.5", "2.0", 40)));
        f.val.put(CAND, f.candVal(metrics("0.0096", "0.05", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C6")).isEqualTo(Check.Outcome.PASS);
        // 검증 0.0095 / 16 = 0.000594 < 0.0006
        f.val.put(CAND, f.candVal(metrics("0.0095", "0.05", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C6")).isEqualTo(Check.Outcome.FAIL);
    }

    @Test
    void C6_예열_일수를_빼고_나누면_판정이_바뀐다() {
        Fixture f = new Fixture();
        f.train.put(CAND, run(CAND, metrics("0.012", "0.05", "2", "0.5", "2.0", 40)));
        f.val.put(CAND, f.candVal(metrics("0.004", "0.05", "2", "0.5", "2.0", 40)));
        // 예열 0: 학습 0.012/24 = 0.0005, 검증 0.004/16 = 0.00025 → 비율 0.5 < 0.6
        assertThat(f.check("C6")).isEqualTo(Check.Outcome.FAIL);
        // 예열 8: 학습 0.012/16 = 0.00075, 검증 0.004/8 = 0.0005 → 비율 0.667 ≥ 0.6
        f.warmupDays = 8;
        assertThat(f.check("C6")).isEqualTo(Check.Outcome.PASS);
        // 예열이 검증 일수 이상이면 남는 날이 없다
        f.warmupDays = 16;
        assertThat(f.check("C6")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    @Test
    void C6_학습_구간이_손실이면_검증이_좋아도_미달() {
        Fixture f = new Fixture();
        f.train.put(CAND, run(CAND, metrics("-0.001", "0.05", "2", "0.5", "2.0", 40)));
        f.val.put(CAND, f.candVal(metrics("0.10", "0.05", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C6")).isEqualTo(Check.Outcome.FAIL);
        f.train.put(CAND, run(CAND, metrics("0", "0.05", "2", "0.5", "2.0", 40)));
        assertThat(f.check("C6")).isEqualTo(Check.Outcome.FAIL);
    }

    // ---------- C7 ----------

    @Test
    void C7_변형이_원래의_50퍼센트_PF_1_0과_같으면_통과() {
        Fixture f = new Fixture();
        f.train.put(CAND, run(CAND, metrics("0.02", "0.05", "2", "0.5", "2.0", 40)));
        f.low = run("cand-k80", metrics("0.01", "0.05", "2", "0.5", "1.0", 40));
        f.high = run("cand-k120", metrics("0.03", "0.05", "2", "0.5", "1.2", 40));
        assertThat(f.check("C7")).isEqualTo(Check.Outcome.PASS);
        f.low = run("cand-k80", metrics("0.0099", "0.05", "2", "0.5", "1.0", 40));
        assertThat(f.check("C7")).isEqualTo(Check.Outcome.FAIL);
        f.low = run("cand-k80", metrics("0.01", "0.05", "2", "0.5", "0.99", 40));
        assertThat(f.check("C7")).isEqualTo(Check.Outcome.FAIL);
        f.low = null; // 변형 Run 실패
        assertThat(f.check("C7")).isEqualTo(Check.Outcome.FAIL);
    }

    @Test
    void C7_원래가_손실이면_미달_핵심_파라미터가_없으면_판단_불가() {
        Fixture f = new Fixture();
        f.train.put(CAND, run(CAND, metrics("-0.01", "0.05", "2", "0.5", "0.8", 40)));
        f.low = run("cand-k80", metrics("0.05", "0.05", "2", "0.5", "2.0", 40));
        f.high = run("cand-k120", metrics("0.05", "0.05", "2", "0.5", "2.0", 40));
        assertThat(f.check("C7")).isEqualTo(Check.Outcome.FAIL);
        f.perturbed = false;
        assertThat(f.check("C7")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    // ---------- C8 ----------

    @Test
    void C8_손익을_월요일_시작_주로_묶어_최고_주를_뺀다() {
        Fixture f = new Fixture();
        // 1주(4/6 월~4/10 금): +100,000 +50,000 = +150,000 / 2주(4/13 월~): +200,000 −100,000 = +100,000
        // 전체 +250,000, 최고 주 150,000, 나머지 +100,000 > 0
        f.equity = equity(SEED, "2026-04-06", "100000", "2026-04-10", "50000", "2026-04-13", "200000",
                "2026-04-15", "-100000");
        assertThat(f.check("C8")).isEqualTo(Check.Outcome.PASS);
        // 1주 +300,000 / 2주 −100,000: 전체 +200,000이지만 최고 주를 빼면 −100,000
        f.equity = equity(SEED, "2026-04-06", "300000", "2026-04-13", "-100000");
        assertThat(f.check("C8")).isEqualTo(Check.Outcome.FAIL);
        // 금요일과 다음 월요일은 다른 주다: 금 +100,000, 월 +100,000 → 두 주가 각각 100,000, 나머지 100,000 > 0
        f.equity = equity(SEED, "2026-04-10", "100000", "2026-04-13", "100000");
        assertThat(f.check("C8")).isEqualTo(Check.Outcome.PASS);
        // 같은 주의 이틀은 합친다: 1주 +100,000 +100,000 = +200,000, 2주 −50,000 → 최고 주를 빼면 −50,000
        // (날짜별로 묶었다면 최고 하루 100,000을 빼고 +50,000이라 통과로 잘못 판정된다)
        f.equity = equity(SEED, "2026-04-06", "100000", "2026-04-07", "100000", "2026-04-13", "-50000");
        assertThat(f.check("C8")).isEqualTo(Check.Outcome.FAIL);
        f.equity = List.of();
        assertThat(f.check("C8")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    // ---------- C9 ----------

    @Test
    void C9_지연_2초에서_수익이고_PF_1_0_이상이어야_통과() {
        Fixture f = new Fixture();
        f.stress.put(CAND, run(CAND, metrics("0.0001", "0.05", "2", "0.5", "1.0", 40)));
        assertThat(f.check("C9")).isEqualTo(Check.Outcome.PASS);
        f.stress.put(CAND, run(CAND, metrics("0.0001", "0.05", "2", "0.5", "0.99", 40)));
        assertThat(f.check("C9")).isEqualTo(Check.Outcome.FAIL);
        f.stress.put(CAND, run(CAND, metrics("0", "0.05", "2", "0.5", "1.5", 40)));
        assertThat(f.check("C9")).isEqualTo(Check.Outcome.FAIL);
        f.stress.remove(CAND);
        assertThat(f.check("C9")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    // ---------- C10 ----------

    @Test
    void C10_대조군_95분위를_엄격하게_넘어야_통과() {
        Fixture f = new Fixture();
        // 대조군 20개 왕복당 −20…−1원 → 95분위 순위 ceil(0.95 × 20) = 19 → −2원
        // 후보 왕복 10건, 최종 평가금액 = 시드 − 20원 → 왕복당 −2원: 같으면 미달
        f.val.put(CAND, f.candVal(new RunMetrics(Money.of(SEED.subtract(new BigDecimal("20")).toPlainString(), Money.KRW),
                new BigDecimal("-0.000002"), null, BigDecimal.ZERO, null, null, null, null, 20, 10, null,
                Money.of(0, Money.KRW), null, null, null)));
        assertThat(f.check("C10")).isEqualTo(Check.Outcome.FAIL);
        // 왕복당 −1원 > −2원
        f.val.put(CAND, f.candVal(new RunMetrics(Money.of(SEED.subtract(new BigDecimal("10")).toPlainString(), Money.KRW),
                new BigDecimal("-0.000001"), null, BigDecimal.ZERO, null, null, null, null, 20, 10, null,
                Money.of(0, Money.KRW), null, null, null)));
        assertThat(f.check("C10")).isEqualTo(Check.Outcome.PASS);
        f.nullCount = 0;
        assertThat(f.check("C10")).isEqualTo(Check.Outcome.NOT_AVAILABLE);
    }

    @Test
    void 분위수는_가장_가까운_순위다() {
        List<BigDecimal> sorted = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            sorted.add(BigDecimal.valueOf(i));
        }
        assertThat(ScreeningCriteria.quantile(sorted, new BigDecimal("0.95"))).isEqualByComparingTo("19");
        assertThat(ScreeningCriteria.quantile(sorted, new BigDecimal("0.951"))).isEqualByComparingTo("20");
        assertThat(ScreeningCriteria.quantile(sorted.subList(0, 1), new BigDecimal("0.95"))).isEqualByComparingTo("1");
        assertThat(ScreeningCriteria.quantile(List.of(), new BigDecimal("0.95"))).isNull();
    }

    @Test
    void 모든_항목을_넘으면_후보이고_기준선_대조군은_후보가_아니다() {
        Fixture f = new Fixture();
        List<Screening> s = ScreeningCriteria.evaluate(f.build());
        Screening cand = s.stream().filter(x -> x.profileId().equals(CAND)).findFirst().orElseThrow();
        assertThat(cand.checks()).extracting(Check::code)
                .containsExactly("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10");
        assertThat(cand.candidate()).as(cand.checks().toString()).isTrue();
        assertThat(s).filteredOn(Screening::reference).extracting(Screening::profileId).containsExactly(BH, RND);
        assertThat(s).filteredOn(Screening::reference).noneMatch(Screening::candidate);
    }

    // ---------- 결과 레코드 만들기 ----------

    /** 최종 평가금액은 시드 × (1 + 수익률). null 문자열은 null 값. */
    private static RunMetrics metrics(String ret, String mdd, String sharpe, String win, String pf, int trips) {
        BigDecimal r = new BigDecimal(ret);
        return new RunMetrics(Money.of(SEED.add(SEED.multiply(r)).toPlainString(), Money.KRW), r, null,
                new BigDecimal(mdd), dec(sharpe), dec(win), dec(pf), null, trips * 2, trips, null,
                Money.of(0, Money.KRW), null, null, null);
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static RunSummary run(String id, RunMetrics m) {
        return run(id, m, List.of());
    }

    private static RunSummary run(String id, RunMetrics m, List<EquityPoint> equity) {
        return new RunSummary(id, "h-" + id, RunStatus.COMPLETED, null, m, equity, 0, new TreeMap<>());
    }

    /** (날짜, 그날 손익) 쌍으로 일별 평가금액을 만든다. */
    private static List<EquityPoint> equity(BigDecimal seed, String... dayAndPnl) {
        List<EquityPoint> out = new ArrayList<>();
        BigDecimal e = seed;
        for (int i = 0; i < dayAndPnl.length; i += 2) {
            LocalDate d = LocalDate.parse(dayAndPnl[i]);
            e = e.add(new BigDecimal(dayAndPnl[i + 1]));
            Money m = Money.of(e.toPlainString(), Money.KRW);
            out.add(new EquityPoint(d, d.atTime(15, 30).toInstant(ZoneOffset.ofHours(9)), m, m));
        }
        return out;
    }

    /** 기본값은 모든 항목을 넘는 후보 하나와 기준선·대조군. 테스트가 필요한 칸만 바꾼다. */
    private static final class Fixture {
        final Map<String, RunSummary> val = new HashMap<>();
        final Map<String, RunSummary> train = new HashMap<>();
        final Map<String, RunSummary> stress = new HashMap<>();
        List<EquityPoint> equity = equity(SEED, "2026-04-06", "200000", "2026-04-13", "150000", "2026-04-20",
                "150000");
        RunSummary low = run("cand-k80", metrics("0.05", "0.05", "2", "0.5", "2.0", 40));
        RunSummary high = run("cand-k120", metrics("0.05", "0.05", "2", "0.5", "2.0", 40));
        boolean perturbed = true;
        int warmupDays;
        int nullCount = 20;

        Fixture() {
            val.put(BH, run(BH, metrics("0.01", "0.05", "1", null, null, 0)));
            val.put(RND, run(RND, metrics("-0.05", "0.06", "-3", "0.3", "0.4", 96)));
            val.put(CAND, candVal(metrics("0.05", "0.05", "2", "0.5", "2.0", 40)));
            train.put(BH, run(BH, metrics("0.01", "0.05", "1", null, null, 0)));
            train.put(RND, run(RND, metrics("-0.05", "0.06", "-3", "0.3", "0.4", 96)));
            train.put(CAND, run(CAND, metrics("0.05", "0.05", "2", "0.5", "2.0", 40)));
            stress.putAll(val);
        }

        /** 검증 구간 후보 Run. 자산 곡선은 {@link #equity}를 build 때 붙인다. */
        RunSummary candVal(RunMetrics m) {
            return run(CAND, m);
        }

        Check.Outcome check(String code) {
            List<Screening> s = ScreeningCriteria.evaluate(build());
            return s.stream().filter(x -> x.profileId().equals(CAND)).findFirst().orElseThrow().checks().stream()
                    .filter(c -> c.code().equals(code)).findFirst().orElseThrow().outcome();
        }

        StudyResult build() {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("signal.x", "10");
            if (warmupDays > 0) {
                params.put("study.warmup-days", Integer.toString(warmupDays));
            }
            List<StrategyProfile> profiles = List.of(
                    new StrategyProfile(BH, 1, Market.KRX, TradingStyle.SWING, new TreeMap<>()),
                    new StrategyProfile(CAND, 1, Market.KRX, TradingStyle.INTRADAY, params),
                    new StrategyProfile(RND, 1, Market.KRX, TradingStyle.INTRADAY, new TreeMap<>()));
            StudyConfig config = new StudyConfig(new ExperimentId("EXP-TEST"), 1L, Money.of(SEED.longValue(), Money.KRW),
                    profiles, BH, RND, 24, List.of(BASE, STRESS), BASE, nullCount, ScreeningThresholds.defaults(), 1);

            Map<String, RunSummary> valRuns = new HashMap<>(val);
            RunSummary c = valRuns.get(CAND);
            if (c != null) {
                valRuns.put(CAND, run(CAND, c.metrics(), equity));
            }
            List<ExperimentOutcome> grid = List.of(
                    outcome("TRAIN", 24, BASE, train),
                    outcome("VALIDATION", 16, BASE, valRuns),
                    outcome("VALIDATION", 16, STRESS, stress));

            Map<String, RunSummary> perturbRuns = new HashMap<>();
            if (low != null) {
                perturbRuns.put("cand-k80", low);
            }
            if (high != null) {
                perturbRuns.put("cand-k120", high);
            }
            Map<String, StrategyStudy.PerturbedPair> pairs = new LinkedHashMap<>();
            if (perturbed) {
                pairs.put(CAND, new StrategyStudy.PerturbedPair("signal.x", "8", "12", "cand-k80", "cand-k120"));
            }

            // 대조군 i번째: 왕복 10건, 왕복당 −(21 − i)원 → −20 … −1
            List<ExperimentOutcome> nulls = new ArrayList<>();
            for (int i = 1; i <= nullCount; i++) {
                BigDecimal pnl = BigDecimal.valueOf(-(21 - i) * 10L);
                RunMetrics m = new RunMetrics(Money.of(SEED.add(pnl).toPlainString(), Money.KRW),
                        pnl.divide(SEED), null, BigDecimal.ZERO, null, null, null, null, 20, 10, null,
                        Money.of(0, Money.KRW), null, null, null);
                nulls.add(outcome("NULL-" + i, 16, BASE, Map.of(RND, run(RND, m))));
            }
            return new StudyResult(config, "TEST", List.of(), grid,
                    outcome("PERTURB", 24, BASE, perturbRuns), pairs, nulls, true, List.of());
        }

        private static ExperimentOutcome outcome(String period, int days, Duration latency, Map<String, RunSummary> runs) {
            return new ExperimentOutcome("EXP-TEST-" + period, period, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 4, 24),
                    days, 1L, latency, "BOOK-V1", "cost", Map.copyOf(runs));
        }
    }
}
