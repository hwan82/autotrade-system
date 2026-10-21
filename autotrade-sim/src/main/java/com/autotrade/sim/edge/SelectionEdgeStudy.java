package com.autotrade.sim.edge;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.calendar.ExchangeCalendar;
import com.autotrade.marketdata.daily.DailyBar;
import com.autotrade.marketdata.daily.InstrumentPeriod;
import com.autotrade.marketdata.rules.KrxMarketRules;
import com.autotrade.selection.calc.Metric;
import com.autotrade.selection.calc.SelectionCalculator;
import com.autotrade.selection.calc.SelectionCriteria;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.selection.profile.SelectionRefresh;
import com.autotrade.selection.result.SelectionResult;
import com.autotrade.sim.combo.CombinationExperiment;
import com.autotrade.sim.combo.SnapshotInputs;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentProgress;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.RunGate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * 선정 엣지 일봉 사전 검사(ADR-012 3.3·배치표 2b, D-125). 선정 프로필이 고른 종목을 선정 기준일 T 시가에 사서 T 종가에 판다고
 * 보고, 같은 날·같은 수를 하드 필터 통과 집합에서 무작위로 고른 대조군과 비교한다. 전략·주문·계좌를 쓰지 않는 선정 수준의
 * 통계라 승격 근거가 아니라 실시간 모의(LIVE02) 전에 거르는 단계다.
 *
 * <p><b>왜 워크포워드 실행기(ADR-009)가 아닌가</b>: 일봉 경로 {@code DAILY-PATH-V1}의 마지막 칸은 15:00 종가이고 장중 프로필의
 * 장 마감 청산 시각(15:15 등)에 해당하는 이벤트가 없어 같은 날 종가 청산을 재현하지 못한다 {@code [추론]}. 또 워크포워드의
 * 대조군은 "같은 선정 + 무작위 진입"이라 이 검사가 필요한 "무작위 선정"(PR #191 strategy-researcher n2)과 다르다.
 *
 * <p><b>시점 규칙</b>(미래 정보 0건): 선정은 {@link SelectionCalculator}가 기준 데이터일 c = T의 직전 거래일까지 일봉으로 계산한다.
 * 대조군 모집단(하드 필터 통과 집합)도 같은 결과에서 나온다(c 기준). T 일봉(시가·고가·저가·종가·거래량)은 결과 변수에만 쓰고
 * 선정·표본 뽑기에는 쓰지 않는다. T에 일봉이 없거나 거래량이 0 이하(거래정지)인 종목은 후보·대조군 모두 "거래 불가"로 같게 뺀다.
 *
 * <p><b>결과 변수</b>(종목·T 하나 = 왕복 하나, 같은 금액 {@code trade-notional})
 * <ul>
 *   <li>{@code range} = (T 고가 − T 저가) ÷ T 시가. 비용과 무관한 장중 변동폭(분모가 c−1 종가인 지표 {@code day-range}와 다름,
 *       PR #193 R4).</li>
 *   <li>{@code net} = 시가 × (1 + 슬리피지)를 호가 단위로 올린 값에 사고 종가 × (1 − 슬리피지)를 내린 값에 판 비용 차감 수익률.
 *       수량 = max(1, ⌊금액 ÷ 매수가⌋), 수수료·세금은 {@link CostModel#fees}(실험과 같은 비용 모델). 가상 거래소의 호가 없는
 *       시장가 체결 규칙과 같은 식이다.</li>
 * </ul>
 *
 * <p><b>통계</b>: 구간마다 왕복을 모은 평균(pooled). 대조군 시드 i의 그날 표본은 시드 {@code (seed, i, T)}로 정해 병렬 수·순서와
 * 무관하다. 판정 규칙은 {@link EdgeJudge}.
 */
public final class SelectionEdgeStudy {

    /** 결과 모델 버전. 결과 변수·체결 근사·표본 규칙을 바꾸면 올린다. */
    public static final String MODEL = "EDGE-DAILY-V1";
    static final String REGISTRATION = "edge.yaml";
    private static final String FILTER_PREFIX = "FILTER_";

    private SelectionEdgeStudy() {
    }

    /** 결과 파일(이름 → 내용)과 결정론 확인 결과(확인하지 않았으면 null). */
    public record Outcome(Map<String, String> files, Boolean deterministic, EdgeJudge.Verdict verdict) {
    }

    /**
     * 하루 기록. 배열은 하드 필터 통과 종목(코드 순) 기준이다. {@code Double.NaN}은 T에 거래 불가.
     *
     * @param segment 구간 번호. 판정 구간 0..W−1, 기술 통계 구간 W..
     * @param selected 선정 종목의 하드 필터 통과 배열 위치(선정 순위 순)
     * @param gatePassed 자격 조건을 모두 통과한 종목 수(상위 N 자르기 전)
     * @param soleNoData 결측 사유 하나로만 빠진 종목 수(사유 코드별)
     */
    record Day(LocalDate t, LocalDate c, int segment, int[] selected, double[] range, double[] net,
            boolean[] openAtHigh, int gatePassed, Map<String, Integer> soleNoData) {

        int hardPass() {
            return range.length;
        }
    }

    /** 등록 파일을 읽어 검사를 돌리고 결과 파일을 등록 파일 옆에 쓴다. 멈추면 아무것도 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        Path file = experimentDir.resolve(REGISTRATION);
        EdgePlan plan;
        String sha;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            plan = EdgePlan.parse(text, file.toString());
            sha = sha256(text.replace("\r\n", "\n"));
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 읽을 수 없습니다: " + file, e);
        }
        if (!plan.id().equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + plan.id());
        }
        Outcome out = run(plan, sha, repoRoot, snapshotsRoot, code, verifyDeterminism, log, gate);
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            Path target = experimentDir.resolve(f.getKey());
            try {
                Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
            }
            log.accept("작성: " + target);
        }
        return out;
    }

    static Outcome run(EdgePlan plan, String registrationSha, Path repoRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        SelectionProfile profile = CombinationExperiment.loadSelection(repoRoot, plan.selection());
        if (profile.market() != plan.market()) {
            throw new IllegalArgumentException("선정 프로필 시장이 등록 파일과 다릅니다: " + profile.market());
        }
        if (profile.refresh() == SelectionRefresh.POST_CLOSE_WEEKLY) {
            throw new IllegalArgumentException("주간 갱신 선정은 이 검사(T 하루 적용)에 쓸 수 없습니다: " + profile.key());
        }
        LocalDate from = earliest(plan);
        LocalDate to = latest(plan);
        ExchangeCalendar calendar = ExchangeCalendar.bundled(plan.market());
        SelectionCriteria criteria = SelectionCriteria.parse(profile);
        boolean usesDisclosures = criteria.inputKinds().contains(Metric.Kind.DISCLOSURE);
        if (usesDisclosures != plan.disclosuresSnapshot().isPresent()) {
            throw new IllegalArgumentException(usesDisclosures
                    ? "선정이 공시 지표를 쓰는데 disclosures-snapshot이 없습니다: " + profile.key()
                    : "선정이 공시 지표를 쓰지 않는데 disclosures-snapshot이 있습니다: " + profile.key());
        }
        if (criteria.inputKinds().contains(Metric.Kind.FINANCIAL) || criteria.inputKinds().contains(Metric.Kind.SHARES)) {
            throw new IllegalArgumentException("재무·상장주식수 지표를 쓰는 선정은 이 검사가 아직 받지 않습니다: " + profile.key());
        }
        SnapshotInputs in = SnapshotInputs.load(plan.market(), plan.barsSnapshot(), Optional.empty(),
                plan.disclosuresSnapshot(), snapshotsRoot, calendar, gate, log, (first, last) -> {
                    if (!from.isAfter(first)) {
                        throw new IllegalArgumentException("첫 구간 시작 " + from + "이 스냅샷 첫 날 " + first
                                + "보다 뒤여야 합니다(기준 데이터일·조회 기간)");
                    }
                    if (to.isAfter(last)) {
                        throw new IllegalArgumentException("마지막 구간 끝 " + to + "이 스냅샷 마지막 거래일 " + last + "보다 뒤입니다");
                    }
                });
        gate.check();
        Map<Symbol, InstrumentPeriod> periods = periods(in, from, to);
        KrxMarketRules rules = SnapshotInputs.rules(calendar, periods);
        CostModel cost = SnapshotInputs.krCost(periods);
        if (!cost.covers(from, to)) {
            throw new IllegalArgumentException("비용 표가 검사 기간을 덮지 않습니다: " + from + " ~ " + to);
        }
        List<LocalDate> tradingDays = in.days().dates().stream()
                .filter(d -> segment(plan, d) >= 0).toList();
        if (tradingDays.isEmpty()) {
            throw new IllegalArgumentException("구간 안에 거래일이 없습니다");
        }
        int threads = Math.max(1, Math.min(plan.parallelism(), gate.maxParallelRuns()));
        log.accept("선정 " + profile.key() + ", 거래일 " + tradingDays.size() + "일, 대조군 시드 " + plan.nullSeeds()
                + ", 병렬 " + threads);
        EdgeSurvivorship.Result survivorship = null;
        if (usesDisclosures) {
            gate.check();
            log.accept("공시 생존편향 확인(연 단위 덮임률)");
            java.util.SortedSet<String> types = new TreeSet<>();
            criteria.gates().forEach(g -> types.addAll(g.types()));
            survivorship = EdgeSurvivorship.check(in.bars(), in.disclosures(), types,
                    in.days().dates().stream().filter(d -> !d.isAfter(in.last())).toList(), in.last());
        }
        List<Day> days = days(plan, profile, in, rules, cost, tradingDays, threads, gate);
        Map<String, String> files = render(plan, registrationSha, profile, in, cost, from, to, code, days, survivorship,
                null);
        Boolean deterministic = null;
        if (verifyDeterminism) {
            log.accept("결정론 확인: 병렬 1로 다시 계산합니다");
            List<Day> again = days(plan, profile, in, rules, cost, tradingDays, 1, gate);
            Map<String, String> second = render(plan, registrationSha, profile, in, cost, from, to, code, again,
                    survivorship, null);
            deterministic = second.equals(files);
            files = render(plan, registrationSha, profile, in, cost, from, to, code, days, survivorship, deterministic);
        }
        EdgeJudge.Result judged = EdgeJudge.judge(plan, days, survivorship);
        return new Outcome(files, deterministic, judged.verdict());
    }

    // ---------------------------------------------------------------- 날마다

    /** 거래일마다 선정을 계산하고 결과 변수를 만든다. 순서는 거래일 순으로 고정(병렬 수와 무관). */
    static List<Day> days(EdgePlan plan, SelectionProfile profile, SnapshotInputs in, KrxMarketRules rules,
            CostModel cost, List<LocalDate> tradingDays, int threads, RunGate gate) {
        SelectionCalculator calc = new SelectionCalculator(in.selectionData());
        String id = plan.id();
        int total = tradingDays.size();
        report(gate, new ExperimentProgress(id, ExperimentProgress.Phase.STARTED, 1, 1, 0, 0, total, 0));
        List<Day> out = new ArrayList<>(total);
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "edge-day");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Day>> futures = new ArrayList<>(total);
            for (LocalDate t : tradingDays) {
                futures.add(pool.submit(() -> {
                    gate.check();
                    return day(plan, profile, calc, in, rules, cost, t);
                }));
            }
            for (int i = 0; i < total; i++) {
                out.add(get(futures.get(i)));
                if ((i + 1) % 20 == 0 || i + 1 == total) {
                    report(gate, new ExperimentProgress(id, ExperimentProgress.Phase.RUNNING, 1, 1, 0, 0, total, i + 1));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        report(gate, new ExperimentProgress(id, ExperimentProgress.Phase.FINISHED, 1, 1, 1, 0, total, total));
        return out;
    }

    private static Day get(Future<Day> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExperimentStoppedException("중단되었습니다");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static void report(RunGate gate, ExperimentProgress p) {
        try {
            gate.onProgress(p);
        } catch (RuntimeException ignored) {
            // 진행 보고 실패는 결과에 영향이 없다(RunGate 계약)
        }
    }

    static Day day(EdgePlan plan, SelectionProfile profile, SelectionCalculator calc, SnapshotInputs in,
            KrxMarketRules rules, CostModel cost, LocalDate t) {
        SelectionResult r = calc.calculate(profile, t);
        LocalDate c = profile.refresh().cutoff(t, in.days().days()::previous); // LAG1은 T−2(D-137)
        List<String> hard = new ArrayList<>();
        int gatePassed = r.selected().size();
        Map<String, Integer> soleNoData = new TreeMap<>();
        for (SelectionResult.Evaluation e : r.evaluations()) { // 코드 순
            if (e.reasons().stream().anyMatch(x -> x.startsWith(FILTER_PREFIX))) {
                continue;
            }
            hard.add(e.code());
            if (e.reasons().contains(SelectionCalculator.RANK_CUTOFF)) {
                gatePassed++;
            }
            if (e.reasons().size() == 1 && e.reasons().get(0).endsWith("_" + SelectionCalculator.NO_DATA)) {
                soleNoData.merge(e.reasons().get(0), 1, Integer::sum);
            }
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < hard.size(); i++) {
            index.put(hard.get(i), i);
        }
        int[] selected = new int[r.selected().size()];
        for (int i = 0; i < selected.length; i++) {
            Integer at = index.get(r.selected().get(i).code());
            if (at == null) {
                throw new IllegalStateException("선정 종목이 하드 필터 통과 집합에 없습니다: " + r.selected().get(i).code());
            }
            selected[i] = at;
        }
        double[] range = new double[hard.size()];
        double[] net = new double[hard.size()];
        boolean[] openAtHigh = new boolean[hard.size()];
        for (int i = 0; i < hard.size(); i++) {
            Symbol s = new Symbol(plan.market(), hard.get(i));
            List<DailyBar> bars = in.bars().bars(s, t, t);
            if (bars.isEmpty() || bars.get(0).volume() <= 0) {
                range[i] = Double.NaN;
                net[i] = Double.NaN;
                continue;
            }
            DailyBar b = bars.get(0);
            range[i] = range(b);
            net[i] = net(b, s, t, rules, cost, plan.tradeNotional());
            openAtHigh[i] = b.open().compareTo(b.high()) == 0;
        }
        return new Day(t, c, segment(plan, t), selected, range, net, openAtHigh, gatePassed, soleNoData);
    }

    /** (고가 − 저가) ÷ 시가. */
    static double range(DailyBar b) {
        return b.high().subtract(b.low()).divide(b.open(), MathContext.DECIMAL64).doubleValue();
    }

    /** T 시가 매수(슬리피지·호가 올림) → T 종가 매도(슬리피지·호가 내림)의 비용 차감 수익률. */
    static double net(DailyBar b, Symbol s, LocalDate t, KrxMarketRules rules, CostModel cost, BigDecimal notional) {
        BigDecimal slip = cost.slippageRate();
        BigDecimal buy = rules.roundToTick(s, b.open().multiply(BigDecimal.ONE.add(slip)), RoundingMode.CEILING);
        BigDecimal sell = rules.roundToTick(s, b.close().multiply(BigDecimal.ONE.subtract(slip)), RoundingMode.FLOOR);
        long qty = Math.max(1L, notional.divide(buy, 0, RoundingMode.FLOOR).longValueExact());
        BigDecimal q = BigDecimal.valueOf(qty);
        BigDecimal paid = buy.multiply(q).add(cost.fees(Side.BUY, s, t, buy, qty).total().amount());
        BigDecimal got = sell.multiply(q).subtract(cost.fees(Side.SELL, s, t, sell, qty).total().amount());
        return got.divide(paid, MathContext.DECIMAL64).subtract(BigDecimal.ONE).doubleValue();
    }

    // ---------------------------------------------------------------- 대조군 표본

    /**
     * 대조군 시드 {@code i}가 거래일 {@code t}에 하드 필터 통과 {@code population}종목에서 고르는 {@code k}개 위치(Floyd 표본,
     * 중복 없음, 순서 무관). 시드는 (실험 시드, i, t)로만 정해진다.
     */
    static int[] sample(long seed, int i, LocalDate t, int population, int k) {
        if (k > population) {
            throw new IllegalArgumentException("표본 수가 모집단보다 큽니다: " + k + " > " + population);
        }
        SplittableRandom rnd = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + (i + 1L) * 0xBF58476D1CE4E5B9L
                + t.toEpochDay() * 0x94D049BB133111EBL);
        int[] chosen = new int[k];
        int n = 0;
        for (int j = population - k; j < population; j++) {
            int v = rnd.nextInt(j + 1);
            boolean seen = false;
            for (int x = 0; x < n; x++) {
                if (chosen[x] == v) {
                    seen = true;
                    break;
                }
            }
            chosen[n++] = seen ? j : v;
        }
        return chosen;
    }

    // ---------------------------------------------------------------- 도움

    /** 거래일이 든 구간 번호. 판정 구간 0..W−1, 기술 통계 구간 W.., 어디에도 없으면 −1. */
    static int segment(EdgePlan plan, LocalDate d) {
        for (int i = 0; i < plan.windows().size(); i++) {
            if (plan.windows().get(i).contains(d)) {
                return i;
            }
        }
        for (int i = 0; i < plan.descriptive().size(); i++) {
            if (plan.descriptive().get(i).contains(d)) {
                return plan.windows().size() + i;
            }
        }
        return -1;
    }

    private static LocalDate earliest(EdgePlan plan) {
        LocalDate d = plan.windows().get(0).from();
        for (EdgePlan.Window w : plan.descriptive()) {
            d = w.from().isBefore(d) ? w.from() : d;
        }
        return d;
    }

    private static LocalDate latest(EdgePlan plan) {
        LocalDate d = plan.windows().get(plan.windows().size() - 1).to();
        for (EdgePlan.Window w : plan.descriptive()) {
            d = w.to().isAfter(d) ? w.to() : d;
        }
        return d;
    }

    /** 종목 → 구간 안 마지막 일봉일의 종목 이력(소속부·유형). 워크포워드와 같은 단순화(Q-012). */
    private static Map<Symbol, InstrumentPeriod> periods(SnapshotInputs in, LocalDate from, LocalDate to) {
        TreeSet<Symbol> universe = new TreeSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            universe.addAll(in.bars().universe(Market.KRX, d));
        }
        Map<Symbol, InstrumentPeriod> out = new HashMap<>();
        for (Symbol s : universe) {
            List<DailyBar> bars = in.bars().bars(s, from, to);
            if (!bars.isEmpty()) {
                in.bars().instrumentOn(s, bars.get(bars.size() - 1).date()).ifPresent(p -> out.put(s, p));
            }
        }
        return out;
    }

    private static Map<String, String> render(EdgePlan plan, String registrationSha, SelectionProfile profile,
            SnapshotInputs in, CostModel cost, LocalDate from, LocalDate to, CodeVersion code, List<Day> days,
            EdgeSurvivorship.Result survivorship, Boolean deterministic) {
        EdgeJudge.Result judged = EdgeJudge.judge(plan, days, survivorship);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("results.md", EdgeReport.results(plan, profile, judged, days, in.selectionData().barsSnapshotId(),
                in.disclosures() == null ? null : in.disclosures().snapshotId(), cost.id(), from, to));
        files.put("windows.tsv", EdgeReport.windowsTsv(plan, judged));
        files.put("days.tsv", EdgeReport.daysTsv(plan, days));
        files.put("null.tsv", EdgeReport.nullTsv(judged));
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("experiment", plan.id());
        inputs.put("edge-sha256", registrationSha);
        inputs.put("model", MODEL);
        inputs.put("bars-snapshot", in.selectionData().barsSnapshotId());
        inputs.put("bars-sha256", in.selectionData().barsSha256());
        inputs.put("calendar-digest", in.selectionData().calendarDigest());
        if (in.disclosures() != null) {
            inputs.put("disclosures-snapshot", in.disclosures().snapshotId());
            inputs.put("disclosures-sha256", in.disclosures().sha256());
        }
        inputs.put("selection", profile.key());
        inputs.put("selection-hash", profile.selectionHash());
        inputs.put("test", plan.test().name());
        inputs.put("family-size", Integer.toString(plan.familySize()));
        inputs.put("cost-model", cost.id());
        inputs.put("cost-table-sha256", cost.tableHash(from, to));
        inputs.put("slippage", cost.slippageRate().toPlainString());
        inputs.put("trade-notional", plan.tradeNotional().toPlainString());
        inputs.put("seed", Long.toString(plan.seed()));
        inputs.put("null-seeds", Integer.toString(plan.nullSeeds()));
        inputs.put("holdout-from", plan.holdoutFrom().toString());
        inputs.put("code-commit", code.commit());
        inputs.put("code-dirty", Boolean.toString(code.dirty()));
        inputs.put("determinism", deterministic == null ? "not-checked" : deterministic ? "same" : "DIFFERENT");
        StringBuilder sb = new StringBuilder();
        inputs.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        files.put("inputs.yaml", sb.toString());
        return files;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
