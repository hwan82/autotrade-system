package com.autotrade.sim.live;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentId;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.recording.RecordedMarketDataSet;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentProgress;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.MarketEventStream;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner;
import com.autotrade.sim.run.SimulationExperimentRunner.Lane;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.study.Check;
import com.autotrade.sim.study.ScreeningCriteria;
import com.autotrade.sim.study.ScreeningThresholds;
import com.autotrade.sim.study.StrategyStudy;
import com.autotrade.strategy.StrategyProfile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 6단계 녹화 판정(6단계 계획 5장 T2, D-116): 끝난 실시간 모의 세션의 입력 기록으로 장중 승격 후보 기준 L1~L11(D-114)을 계산해
 * {@code experiments/<실험ID>/}{@value #FILE}에 쓴다.
 *
 * <p>실시간 세션은 등록된 계좌만 돌리므로, 판정에 필요한 변형은 같은 입력 기록으로 다시 돌린다. 원래 성과(L1~L6·L8)는 입력 기록
 * 리플레이(= 실시간 결과, D-076 합격선)이고, 그것이 세션이 남긴 {@value LiveSession#LIVE_DETAIL_FILE}과 바이트로 같은지(무사고
 * I3)도 함께 본다. 돌리는 것(레인):
 * <ul>
 *   <li>BASE: 등록 파일 그대로(기본 지연 0.5초)</li>
 *   <li>PERTURB: 판정 대상 프로필마다 {@code study.key-param} ×0.8·×1.2(L7, 4단계 C7과 같은 변형 규칙)</li>
 *   <li>L0·L1500·L2000: 판정 대상 프로필을 주문 지연 0·1.5·2초로(STR-13. L9는 2초)</li>
 *   <li>NULL1~NULLN: 무작위 대조군을 시드 = 실험 시드 + i로(L10, N = 21m − 1: 판정 가능한 최소 20m − 1에 여유 m)</li>
 *   <li>REC: 등록 파일 그대로 같은 날 게이트웨이 녹화(boards만)로(L11, 주간 점검 계좌 대조와 같은 입력)</li>
 * </ul>
 * 입력 기록 레인들은 실행기의 레인 경로({@link SimulationExperimentRunner#runLanes})로 기록을 한 번 읽어 함께 돈다. 레인을 몇 묶음으로
 * 나눠 묶음마다 스레드 하나가 기록을 따로 읽는다(묶음 수 = 병렬 Run 상한과 {@value #MAX_GROUPS} 중 작은 쪽). 레인은 서로 아무것도
 * 공유하지 않아 묶음 나누기는 결과에 영향이 없다. 메모리는 기간과 상관없고(D-115) Run 수에 비례한다.
 *
 * <p>판정 대상은 등록 프로필 중 기준선({@code logic: buy-and-hold})과 대조군({@code logic: random})을 뺀 것이고, 기준선과 대조군은
 * 하나씩 있어야 한다. 무사고 I4(주간 점검)·I5(유효 운용일)는 주간 점검 결과로 본다(이 명령은 계산하지 않음).
 */
public final class LiveJudgement {

    /** 결과 파일 이름(등록 파일 옆). 판정 리포트 {@code report.md}(strategy-researcher)가 이 파일을 근거로 쓴다. */
    public static final String FILE = "judgement.md";
    /** 지연 민감도(STR-13). 2초가 L9 판정 지연이다. */
    static final List<Duration> LATENCIES = List.of(Duration.ZERO, Duration.ofMillis(1500), Duration.ofSeconds(2));
    /** 기록을 따로 읽는 묶음 수 상한(디스크·CPU). */
    static final int MAX_GROUPS = 4;
    static final String BASELINE_LOGIC = "buy-and-hold";
    static final String CONTROL_LOGIC = "random";

    private LiveJudgement() {
    }

    /**
     * @param incident 무사고 I1(실패한 계좌)·I2(블랙리스트 종목 체결)·I3(실시간 ≠ 리플레이) 중 하나라도 깨짐
     */
    public record Outcome(boolean incident, Map<String, String> verdicts, String report) {
        public Outcome {
            verdicts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(verdicts));
        }
    }

    /**
     * @param sessionsRoot 세션 폴더들의 부모(설정 폴더 아래 {@code live-sessions})
     * @param recordingsRoot 게이트웨이 녹화 폴더(설정 폴더 아래 {@code recordings}). L11에 쓴다
     * @throws IllegalArgumentException 입력이 맞지 않을 때(세션이 열려 있음, 실시간 보고서·입력 기록 없음, 기준선·대조군 없음 등)
     * @throws ExperimentStoppedException 정지 확인에서 멈췄을 때(결과 파일 없음)
     */
    public static Outcome run(Path experimentDir, Path repoRoot, Path sessionsRoot, Path recordingsRoot, CodeVersion code,
            Consumer<String> log, RunGate gate) {
        Path file = experimentDir.resolve("experiment.yaml");
        LivePlan plan;
        String liveDetail;
        try {
            plan = LivePlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            Path live = experimentDir.resolve(LiveSession.LIVE_DETAIL_FILE);
            if (!Files.isRegularFile(live)) {
                throw new IllegalArgumentException("실시간 세션 보고서가 없습니다: " + live + " (실험실에서 세션을 끝내면 생깁니다)");
            }
            liveDetail = Files.readString(live, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일이나 실시간 보고서를 읽을 수 없습니다: " + experimentDir, e);
        }
        if (!plan.id().value().equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + plan.id());
        }
        gate.check();
        Path sessionDir = sessionsRoot.toAbsolutePath().resolve(plan.id().value());
        LiveReplayCheck.ensureSessionClosed(sessionDir);
        LiveSetup setup = LiveSetup.of(plan, repoRoot);
        Roles roles = roles(setup);
        List<Path> days = LiveReplayCheck.journalDays(setup, sessionDir);
        if (days.isEmpty()) {
            throw new IllegalArgumentException("입력 기록이 없습니다: " + sessionDir.resolve(LiveSession.JOURNAL_DIR));
        }
        StreamedRecordingDataSet journal = StreamedRecordingDataSet.open(plan.market(), days, gate::check, e ->
                RecordingExperiment.offStockTick(setup.rules(), e).ifPresent(price -> {
                    throw new IllegalArgumentException("입력 기록에 주식 호가 단위에 맞지 않는 가격이 있습니다: " + e.symbol().code()
                            + " " + price.toPlainString() + " (sequence " + e.sequence() + ")");
                }));
        List<LocalDate> journalDates = journal.days().stream().map(RecordedMarketDataSet.DayInfo::date).toList();
        log.accept("입력 기록 " + journalDates.size() + "일(" + journalDates.get(0) + " ~ " + journalDates.get(journalDates.size() - 1)
                + "), 이벤트 " + journal.eventCount());

        ScreeningThresholds t = ScreeningThresholds.defaults();
        int m = roles.judged().size();
        // L10 판정이 가능한 가장 작은 N(N + 1 = 20m)에 판정 대상 수만큼 여유를 둔다: 대조군 몇 개가 실패하거나 왕복 0건이어도
        // 모든 계좌의 L10이 판정 불가로 떨어지지 않게(PR #180 strategy-researcher R1, 결과를 보기 전에 고정)
        int nullSeeds = nullSeeds(m);
        Plan lanes = lanes(setup, roles, journal.version(), nullSeeds, log);
        gate.check();
        // 묶음 수 + 녹화 리플레이 한 번(D-098 명령 전체 진행률)
        List<List<Lane>> groups = groups(lanes.all(), Math.min(MAX_GROUPS, Math.max(1, gate.maxParallelRuns())));
        try {
            gate.onExperimentsPlanned(groups.size() + 1);
        } catch (RuntimeException e) {
            // 진행 보고 실패는 판정을 멈추지 않는다
        }
        log.accept("입력 기록 레인 " + lanes.all().size() + "개(Run " + lanes.all().stream()
                .mapToInt(l -> l.spec().combinations().size()).sum() + "개)를 " + groups.size() + "묶음으로 돌립니다");
        Map<String, SimulationReport> byId = runGroups(setup, journal, groups, code, plan.id().value(), gate);

        SimulationReport base = byId.get(lanes.base().spec().id().value());
        String detail = ReportWriter.render(base);
        int diff = LiveReplayCheck.firstDifference(LiveReplayCheck.withoutCodeLine(liveDetail),
                LiveReplayCheck.withoutCodeLine(detail));

        gate.check();
        Recording rec = recording(setup, recordingsRoot, journalDates, code, gate, log);

        Judged judged = judge(setup, roles, lanes, byId, rec, t, m);
        String report = render(setup, code, journal, lanes, byId, rec, judged, diff, codeLine(liveDetail), t, m, nullSeeds);
        Map<String, String> verdicts = new LinkedHashMap<>();
        judged.judgements().forEach((k, v) -> verdicts.put(k, v.verdict().label()));
        return new Outcome(judged.incident() || diff >= 0, verdicts, report);
    }

    /** {@link #run} 후 결과를 등록 파일 옆 {@value #FILE}에 쓴다. 멈추면 쓰지 않는다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path sessionsRoot, Path recordingsRoot,
            CodeVersion code, Consumer<String> log, RunGate gate) {
        Outcome out = run(experimentDir, repoRoot, sessionsRoot, recordingsRoot, code, log, gate);
        Path target = experimentDir.resolve(FILE);
        try {
            Files.writeString(target, out.report(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
        }
        log.accept("작성: " + target);
        return out;
    }

    /** 등록 프로필의 역할(계좌 순서). */
    record Roles(int baseline, int control, List<Integer> judged) {
    }

    static Roles roles(LiveSetup setup) {
        int baseline = -1;
        int control = -1;
        List<Integer> judged = new ArrayList<>();
        List<Combination> combos = setup.combinations();
        for (int i = 0; i < combos.size(); i++) {
            String logic = combos.get(i).profile().parameters().get("logic");
            if (BASELINE_LOGIC.equals(logic)) {
                if (baseline >= 0) {
                    throw new IllegalArgumentException("기준선(logic: buy-and-hold) 프로필이 둘 이상입니다");
                }
                baseline = i;
            } else if (CONTROL_LOGIC.equals(logic)) {
                if (control >= 0) {
                    throw new IllegalArgumentException("대조군(logic: random) 프로필이 둘 이상입니다");
                }
                control = i;
            } else {
                judged.add(i);
            }
        }
        if (baseline < 0 || control < 0 || judged.isEmpty()) {
            throw new IllegalArgumentException("판정에는 기준선(logic: buy-and-hold) 하나, 대조군(logic: random) 하나, 판정 대상 "
                    + "하나 이상이 필요합니다(6단계 계획 2.1)");
        }
        return new Roles(baseline, control, List.copyOf(judged));
    }

    /** 레인 구성. {@code perturbed}는 판정 대상 계좌 번호(0부터) → 변형 쌍(만들 수 없으면 이유). */
    record Plan(Lane base, Lane perturb, Map<Integer, Pair> perturbed, List<Lane> latency, List<Lane> nulls) {
        List<Lane> all() {
            List<Lane> out = new ArrayList<>();
            out.add(base);
            if (perturb != null) {
                out.add(perturb);
            }
            out.addAll(latency);
            out.addAll(nulls);
            return out;
        }
    }

    record Pair(String key, String lowValue, String highValue, String lowId, String highId, String note) {
    }

    static Plan lanes(LiveSetup setup, Roles roles, String dataVersion, int nullSeeds, Consumer<String> log) {
        LivePlan plan = setup.plan();
        ExecutionSettings base = setup.execution();
        Lane baseLane = new Lane(setup.spec(ClockMode.REPLAY, dataVersion), base);
        List<StrategyProfile> judged = roles.judged().stream().map(i -> setup.combinations().get(i).profile()).toList();
        Map<Integer, Pair> pairs = new LinkedHashMap<>();
        List<StrategyProfile> variants = new ArrayList<>();
        for (int i : roles.judged()) {
            StrategyProfile p = setup.combinations().get(i).profile();
            String key = p.parameters().get("study.key-param");
            if (key == null) {
                pairs.put(i, new Pair(null, null, null, null, null, "study.key-param 없음"));
                continue;
            }
            StrategyProfile low = StrategyStudy.Perturbation.scale(p, key, new BigDecimal("0.8"), "k80");
            StrategyProfile high = StrategyStudy.Perturbation.scale(p, key, new BigDecimal("1.2"), "k120");
            if (!valid(setup, low) || !valid(setup, high)) {
                // 변형이 프로필 범위를 벗어나면 L7은 판정 불가(불안정과 섞지 않음, 4단계 C7과 같음)
                pairs.put(i, new Pair(key, low.parameters().get(key), high.parameters().get(key), null, null,
                        "±20% 변형이 파라미터 범위 밖"));
                log.accept(p.id() + ": ±20% 변형이 파라미터 범위 밖이라 L7 판정 불가");
                continue;
            }
            variants.add(low);
            variants.add(high);
            pairs.put(i, new Pair(key, low.parameters().get(key), high.parameters().get(key), low.id(), high.id(), ""));
        }
        Lane perturb = variants.isEmpty() ? null : new Lane(spec(setup, "PERTURB", plan.seed(), variants, dataVersion), base);
        List<Lane> latency = new ArrayList<>();
        for (Duration d : LATENCIES) {
            latency.add(new Lane(spec(setup, "L" + d.toMillis(), plan.seed(), judged, dataVersion), new ExecutionSettings(d)));
        }
        StrategyProfile control = setup.combinations().get(roles.control()).profile();
        List<Lane> nulls = new ArrayList<>();
        for (int i = 1; i <= nullSeeds; i++) {
            nulls.add(new Lane(spec(setup, "NULL" + i, plan.seed() + i, List.of(control), dataVersion), base));
        }
        return new Plan(baseLane, perturb, pairs, List.copyOf(latency), List.copyOf(nulls));
    }

    private static boolean valid(LiveSetup setup, StrategyProfile p) {
        try {
            setup.factory().validate(p);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ExperimentSpec spec(LiveSetup setup, String suffix, long seed, List<StrategyProfile> profiles,
            String dataVersion) {
        LivePlan plan = setup.plan();
        return ExperimentSpec.ofProfiles(new ExperimentId(plan.id().value() + "-" + suffix), plan.from(), plan.to(), seed,
                plan.seedCapital(), setup.cost().id(), dataVersion, ClockMode.REPLAY, profiles);
    }

    /**
     * 레인을 묶음으로 나눈다: 레인 순서대로 Run 수가 가장 적은 묶음에(같으면 앞 묶음에) 넣는다. 정해진 순서라 같은 입력이면 같은
     * 묶음이고, 묶음 나누기는 결과에 영향이 없다(레인끼리 공유하는 것이 없음).
     */
    static List<List<Lane>> groups(List<Lane> lanes, int count) {
        int n = Math.min(count, lanes.size());
        List<List<Lane>> out = new ArrayList<>();
        int[] runs = new int[n];
        for (int i = 0; i < n; i++) {
            out.add(new ArrayList<>());
        }
        for (Lane lane : lanes) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (runs[i] < runs[best]) {
                    best = i;
                }
            }
            out.get(best).add(lane);
            runs[best] += lane.spec().combinations().size();
        }
        return out;
    }

    private static Map<String, SimulationReport> runGroups(LiveSetup setup, MarketEventStream journal,
            List<List<Lane>> groups, CodeVersion code, String id, RunGate gate) {
        // 한 묶음이 실패하면 나머지 묶음도 멈추게 한다. 실행기는 스레드 중단을 보지 않으므로 취소만으로는 남은 묶음이 끝까지
        // 돈다(PR #180 architect N2). 문은 Run 시작·거래일 바뀜에서만 확인되므로, 기록 읽기도 이벤트마다 실패 표시를 보게 해
        // 한 거래일을 더 돌거나 Run이 모두 끝난 묶음이 남은 기록을 끝까지 읽지 않게 한다(PR #185 architect N1)
        AtomicBoolean failed = new AtomicBoolean();
        RunGate shared = abortable(gate, failed);
        MarketEventStream stoppable = abortable(journal, failed);
        List<Callable<List<SimulationReport>>> tasks = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            List<Lane> group = groups.get(g);
            String progressId = id + "-JUDGE-G" + (g + 1);
            tasks.add(() -> runner(setup, stoppable, code, shared).runLanes(group, progressId));
        }
        Map<String, SimulationReport> out = new LinkedHashMap<>();
        for (List<SimulationReport> reports : runAll(tasks, id, () -> failed.set(true))) {
            for (SimulationReport r : reports) {
                out.put(r.result().experimentId().value(), r);
            }
        }
        return out;
    }

    /**
     * 작업을 모두 동시에 돌리고 결과를 작업 순서대로 돌려준다(끝난 순서와 상관없이 같은 결과). 어느 작업이든 실패하면 먼저 끝난
     * 실패를 받는 즉시 {@code onFailure}를 부르고 나머지를 취소한 뒤 그 실패를 올린다. 앞 작업이 끝날 때까지 기다렸다 뒤 작업의
     * 실패를 올리지 않는다(PR #180 architect N2). 이미 돌고 있는 작업은 {@code onFailure}로 멈추게 해야 빨리 끝난다(풀을 닫을 때
     * 남은 작업을 기다린다).
     */
    static <T> List<T> runAll(List<Callable<T>> tasks, String id, Runnable onFailure) {
        List<T> results = new ArrayList<>(Collections.nCopies(tasks.size(), null));
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, tasks.size()))) {
            CompletionService<Integer> done = new ExecutorCompletionService<>(pool);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                int index = i;
                Callable<T> task = tasks.get(i);
                futures.add(done.submit(() -> {
                    results.set(index, task.call());
                    return index;
                }));
            }
            try {
                for (int i = 0; i < tasks.size(); i++) {
                    done.take().get();
                }
            } catch (ExecutionException e) {
                onFailure.run();
                futures.forEach(f -> f.cancel(true));
                if (e.getCause() instanceof RuntimeException r) {
                    throw r; // 정지(ExperimentStoppedException), 입력이 바뀜 등은 그대로 올린다
                }
                throw new IllegalStateException("판정 실행 오류: " + id, e.getCause());
            } catch (InterruptedException e) {
                onFailure.run();
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("판정 실행이 중단됐습니다: " + id, e);
            }
        }
        return results;
    }

    /**
     * {@code failed}가 서면 멈추라고 답하는 문. 그 밖에는 원래 문을 그대로 따른다(진행 보고, 병렬 상한, 사람의 취소). 멈춤 이유는
     * 명령까지 올라가지 않는다: 먼저 실패한 묶음의 예외가 올라간다.
     */
    static RunGate abortable(RunGate gate, AtomicBoolean failed) {
        return new RunGate() {
            @Override
            public Optional<String> stopReason() {
                return failed.get() ? Optional.of("다른 판정 묶음이 실패해 멈춤") : gate.stopReason();
            }

            @Override
            public void check() {
                if (failed.get()) {
                    throw new ExperimentStoppedException("다른 판정 묶음이 실패해 멈춤");
                }
                gate.check();
            }

            @Override
            public void onProgress(ExperimentProgress progress) {
                gate.onProgress(progress);
            }

            @Override
            public void onExperimentsPlanned(int count) {
                gate.onExperimentsPlanned(count);
            }

            @Override
            public int maxParallelRuns() {
                return gate.maxParallelRuns();
            }
        };
    }

    /**
     * 이벤트를 넘기기 전마다 {@code failed}를 보고, 서 있으면 {@link ExperimentStoppedException}을 던지는 기록 읽기. 그 밖에는
     * 원래 읽기를 그대로 따른다(같은 이벤트, 같은 순서, 같은 버전). 실패 표시는 먼저 실패한 묶음이 세우고 그 예외가 명령까지
     * 올라가므로, 여기서 던진 예외는 결과에 들어가지 않는다. 판정 결과에는 영향이 없다(실패가 없으면 아무것도 바꾸지 않음).
     */
    static MarketEventStream abortable(MarketEventStream data, AtomicBoolean failed) {
        return new MarketEventStream() {
            @Override
            public String version() {
                return data.version();
            }

            @Override
            public Market market() {
                return data.market();
            }

            @Override
            public boolean exchangeTimed() {
                return data.exchangeTimed();
            }

            @Override
            public long eventCount() {
                return data.eventCount();
            }

            @Override
            public void forEach(Consumer<MarketEvent> sink) {
                data.forEach(e -> {
                    if (failed.get()) {
                        throw new ExperimentStoppedException("다른 판정 묶음이 실패해 멈춤");
                    }
                    sink.accept(e);
                });
            }
        };
    }

    private static SimulationExperimentRunner runner(LiveSetup setup, MarketEventStream data, CodeVersion code,
            RunGate gate) {
        return new SimulationExperimentRunner(data, setup.rules(), setup.cost(), setup.factory(), setup.risk(),
                setup.execution(), code, 1, setup.benchmark(), null, gate);
    }

    /** 대조군 시드 수: 21m − 1(판정 가능한 최소 20m − 1에 여유 m). */
    static int nullSeeds(int m) {
        return 21 * m - 1;
    }

    /** 녹화 리플레이(L11). 돌리지 못했으면 {@code report}가 null이고 이유가 {@code note}. */
    record Recording(SimulationReport report, String note, long events, int days) {
    }

    private static Recording recording(LiveSetup setup, Path recordingsRoot, List<LocalDate> journalDates, CodeVersion code,
            RunGate gate, Consumer<String> log) {
        LivePlan plan = setup.plan();
        RecordingLayout layout = new RecordingLayout(recordingsRoot.toAbsolutePath());
        LocalDate end = journalDates.get(journalDates.size() - 1);
        List<String> missing = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        // 입력 기록이 있는 날(실험실이 실제로 운용한 날)만 돌린다. 게이트웨이만 켜진 날을 넣으면 운용일 결손(I5)이 L11 괴리로
        // 들어간다(PR #180 strategy-researcher M1). 주간 점검 계좌 대조는 운용 기간 전체 녹화라 날짜 집합이 다를 수 있다.
        for (LocalDate d : journalDates) {
            Path dir = layout.dayDir(plan.market(), d);
            if (Files.isRegularFile(dir.resolve(RecordingLayout.EVENTS_FILE))) {
                dirs.add(dir);
            } else {
                missing.add(d.toString());
            }
        }
        if (!missing.isEmpty()) {
            String note = "같은 날 녹화 없음: " + String.join(", ", missing);
            log.accept("L11: " + note);
            return new Recording(null, note, 0, dirs.size());
        }
        LiveWeeklyCheck.BoardsRecording boards = LiveWeeklyCheck.BoardsRecording.read(setup, dirs, gate);
        if (boards.firstOffTick != null) {
            String note = "녹화에 주식 호가 단위에 맞지 않는 가격이 있음(" + boards.firstOffTick + ")";
            log.accept("L11: " + note);
            return new Recording(null, note, boards.count(), dirs.size());
        }
        // 주간 점검 계좌 대조와 같은 읽기(boards·운용 기간 종목, D-088). 날짜는 입력 기록일만
        MarketEventStream stream = boards.stream("REC-BOARDS-" + plan.id() + "-" + journalDates.get(0) + ".." + end, gate);
        log.accept("녹화 리플레이(L11): 녹화 " + dirs.size() + "일, 이벤트 " + stream.eventCount());
        SimulationReport r = runner(setup, stream, code, gate).runDetailed(setup.spec(ClockMode.REPLAY, stream.version()));
        return new Recording(r, "", stream.eventCount(), dirs.size());
    }

    /** 계좌 번호(0부터) → 판정. 기준선·대조군도 같은 기준으로 판정해 참고로 낸다(6단계 계획 4.2). */
    record Judged(Map<String, LiveJudgeCriteria.Judgement> judgements, boolean incident, List<String> incidents) {
    }

    private static Judged judge(LiveSetup setup, Roles roles, Plan lanes, Map<String, SimulationReport> byId,
            Recording rec, ScreeningThresholds t, int m) {
        LivePlan plan = setup.plan();
        BigDecimal seed = plan.seedCapital().amount();
        SimulationReport base = byId.get(lanes.base().spec().id().value());
        SimulationReport perturb = lanes.perturb() == null ? null : byId.get(lanes.perturb().spec().id().value());
        SimulationReport stress = byId.get(lanes.latency().get(LATENCIES.indexOf(t.stressLatency())).spec().id().value());
        List<BigDecimal> nullPerTrip = new ArrayList<>();
        for (Lane l : lanes.nulls()) {
            BigDecimal v = LiveJudgeCriteria.perTrip(byId.get(l.spec().id().value()).runs().get(0), seed);
            if (v != null) {
                nullPerTrip.add(v);
            }
        }
        nullPerTrip.sort(BigDecimal::compareTo);
        RunReport baseline = base.runs().get(roles.baseline());
        Map<String, LiveJudgeCriteria.Judgement> out = new LinkedHashMap<>();
        for (int i = 0; i < base.runs().size(); i++) {
            RunReport r = base.runs().get(i);
            int judgedIndex = roles.judged().indexOf(i);
            LiveJudgeCriteria.Perturbed perturbed = null;
            RunReport stressRun = null;
            if (judgedIndex >= 0) {
                Pair p = lanes.perturbed().get(i);
                perturbed = p.lowId() == null ? new LiveJudgeCriteria.Perturbed(p.key(), p.lowValue(), p.highValue(), null, null, p.note())
                        : new LiveJudgeCriteria.Perturbed(p.key(), p.lowValue(), p.highValue(), run(perturb, p.lowId()),
                                run(perturb, p.highId()), "");
                stressRun = stress.runs().get(judgedIndex);
            }
            LiveJudgeCriteria.Recorded recorded = new LiveJudgeCriteria.Recorded(
                    rec.report() == null ? null : rec.report().runs().get(i), rec.note());
            out.put(r.summary().accountId().value(), LiveJudgeCriteria.evaluate(new LiveJudgeCriteria.Evidence(r, baseline,
                    perturbed, stressRun, nullPerTrip, m, recorded, seed, plan.from(), t)));
        }
        List<String> incidents = new ArrayList<>();
        Set<String> black = new TreeSet<>(plan.blacklist());
        for (RunReport r : base.runs()) {
            if (!r.summary().failureReason().isEmpty()) {
                incidents.add("I1 " + r.summary().accountId() + " 실패: " + r.summary().failureReason());
            }
            long blackFills = r.fills().stream().filter(f -> black.contains(f.fill().symbol().code())).count();
            if (blackFills > 0) {
                incidents.add("I2 " + r.summary().accountId() + " 블랙리스트 종목 체결 " + blackFills + "건");
            }
        }
        return new Judged(out, !incidents.isEmpty(), incidents);
    }

    private static RunReport run(SimulationReport report, String profileId) {
        for (RunReport r : report.runs()) {
            if (r.profile().id().equals(profileId)) {
                return r;
            }
        }
        throw new IllegalStateException("변형 Run이 없습니다: " + profileId);
    }

    private static String codeLine(String detail) {
        for (String line : detail.split("\n", -1)) {
            if (line.startsWith(LiveReplayCheck.CODE_LINE_PREFIX)) {
                return line;
            }
        }
        return "code 없음";
    }

    static String render(LiveSetup setup, CodeVersion code, StreamedRecordingDataSet journal, Plan lanes,
            Map<String, SimulationReport> byId, Recording rec, Judged judged, int diff, String liveCode,
            ScreeningThresholds t, int m, int nullSeeds) {
        LivePlan plan = setup.plan();
        BigDecimal seed = plan.seedCapital().amount();
        SimulationReport base = byId.get(lanes.base().spec().id().value());
        Roles roles = roles(setup);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.id()).append(" 녹화 판정(6단계 T2, D-116)\n\n");
        sb.append("> 장중 승격 후보 기준 L1~L11(D-114, 6단계 계획 4.2)을 입력 기록으로 계산한 결과. ")
                .append("명령 `runLiveJudgement`가 쓴다. 고치지 않는다(다시 돌리면 같은 내용이 나온다).\n");
        sb.append("> 다중 비교 보정(본페로니 0.05/m)은 L10에만 있다. 나머지 항목은 계좌마다 따로 본 기준이라 판정 대상이 많을수록 ")
                .append("우연히 통과하는 계좌가 생길 수 있다.\n");
        sb.append("> 이 결과는 투자 자문이 아니며, 후보도 표본 외 재검증을 거친 뒤 실전 적용은 사용자가 정한다.\n\n");
        sb.append("- 기간: ").append(plan.from()).append(" ~ ").append(plan.to()).append(", 입력 기록 ")
                .append(journal.days().size()).append("일(").append(journal.days().get(0).date()).append(" ~ ")
                .append(journal.days().get(journal.days().size() - 1).date()).append("), 이벤트 ").append(journal.eventCount())
                .append('\n');
        sb.append("- 코드: ").append(code.commit()).append(code.dirty() ? " dirty" : "").append(" (실시간 세션 ").append(liveCode)
                .append(")\n");
        sb.append("- 데이터 버전: ").append(journal.version()).append('\n');
        sb.append("- 비용: ").append(setup.cost().id()).append(", 체결: ").append(setup.execution().id()).append('\n');
        int runs = lanes.all().stream().mapToInt(l -> l.spec().combinations().size()).sum();
        sb.append("- 돌린 것: 입력 기록 레인 ").append(lanes.all().size()).append("개(Run ").append(runs).append("개), 녹화 리플레이 ")
                .append(rec.report() == null ? "없음(" + rec.note() + ")" : rec.days() + "일 이벤트 " + rec.events())
                .append('\n');
        sb.append("- 판정 대상 m = ").append(m).append(", 대조군 시드 N = ").append(nullSeeds).append(" (실험 시드 + 1 ~ + ")
                .append(nullSeeds).append(", 판정 가능 최소 ").append(20 * m - 1).append("에 여유 ").append(m)
                .append("), L10 문턱 0.05/m = ").append(LiveJudgeCriteria.alpha(m).setScale(5,
                        RoundingMode.HALF_EVEN).toPlainString()).append("\n\n");

        sb.append("## 1. 무사고(I1~I3)\n\n");
        sb.append("- I3 실시간 = 입력 기록 리플레이: ").append(diff < 0 ? "같음(상세 보고서 바이트 일치, code 줄 제외)"
                : "**다름**(code 줄 뺀 " + (diff + 1) + "번째 줄부터). 실시간 경로 결함").append('\n');
        if (judged.incidents().isEmpty()) {
            sb.append("- I1 실패한 계좌 0개, I2 블랙리스트 종목 체결 0건\n");
        } else {
            judged.incidents().forEach(s -> sb.append("- **").append(s).append("**\n"));
        }
        sb.append("- I4(주간 점검 종료 코드)·I5(유효 운용일)는 `weekly/` 결과로 본다(이 명령은 계산하지 않음)\n\n");

        sb.append("## 2. 판정 요약\n\n");
        sb.append("| 계좌 | 프로필 | 역할 | 판정 | L1 | L2 | L3 | L4 | L5 | L6 | L7 | L8 | L9 | L10 | L11 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < base.runs().size(); i++) {
            RunReport r = base.runs().get(i);
            LiveJudgeCriteria.Judgement j = judged.judgements().get(r.summary().accountId().value());
            sb.append("| ").append(r.summary().accountId()).append(" | ").append(r.profile().id()).append('@')
                    .append(r.profile().version()).append(" | ").append(role(roles, i)).append(" | ")
                    .append(roles.judged().contains(i) ? "**" + j.verdict().label() + "**" : "(참고) " + j.verdict().label());
            for (String c : List.of("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10", "L11")) {
                sb.append(" | ").append(mark(j, c));
            }
            sb.append(" |\n");
        }
        sb.append("\n- ○ 통과, ✕ 탈락, ? 판정 불가. 판정: 탈락이 하나라도 있으면 탈락, 아니면 판정 불가가 있으면 보류, 모두 통과면 후보.\n");
        sb.append("- 기준선·대조군은 같은 기준의 참고다(대조군이 L1~L6을 통과하면 기준이 느슨하다는 신호, 6단계 계획 4.2).\n");
        RunReport controlRun = base.runs().get(roles.control());
        LiveJudgeCriteria.Judgement control = judged.judgements().get(controlRun.summary().accountId().value());
        if (control.checks().stream().filter(c -> List.of("L1", "L2", "L3", "L4", "L5", "L6").contains(c.code()))
                .allMatch(c -> c.outcome() == Check.Outcome.PASS)) {
            sb.append("- **대조군 ").append(controlRun.summary().accountId()).append("이 L1~L6을 모두 통과했다. 기준이 느슨하다는 신호다. ")
                    .append("후보가 있어도 결과를 사용자와 함께 다시 본다(6단계 계획 4.2).**\n");
        }
        sb.append('\n');

        sb.append("## 3. 항목별 근거\n\n");
        for (int i = 0; i < base.runs().size(); i++) {
            RunReport r = base.runs().get(i);
            LiveJudgeCriteria.Judgement j = judged.judgements().get(r.summary().accountId().value());
            sb.append("### ").append(r.summary().accountId()).append(' ').append(r.profile().id()).append('@')
                    .append(r.profile().version()).append(" (").append(role(roles, i)).append(", ").append(j.verdict().label())
                    .append(")\n\n");
            for (Check c : j.checks()) {
                sb.append("- ").append(c.code()).append(' ').append(c.title()).append(": ").append(label(c.outcome()))
                        .append(" — ").append(c.detail()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 4. 함께 적을 것(6단계 계획 4.2)\n\n");
        sb.append("| 계좌 | 프로필 | 총수익률 | 벤치마크 ").append(setup.benchmark() == null ? "없음" : setup.benchmark().code())
                .append(" | 장중 노출 | 마감 노출 | 왕복 | 왕복당 순손익 | 블랙리스트 거절 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        Set<String> black = new TreeSet<>(plan.blacklist());
        for (RunReport r : base.runs()) {
            RunMetrics mm = r.summary().metrics();
            long rejected = 0;
            for (OrderRejection j : r.rejections()) {
                if (j.stage().equals("RISK") && black.contains(j.symbol().code())) {
                    rejected++;
                }
            }
            BigDecimal perTrip = LiveJudgeCriteria.perTrip(r, seed);
            sb.append("| ").append(r.summary().accountId()).append(" | ").append(r.profile().id()).append(" | ")
                    .append(mm == null ? "-" : ScreeningCriteria.pct(mm.totalReturn())).append(" | ")
                    .append(mm == null ? "-" : ScreeningCriteria.pct(mm.benchmarkReturn())).append(" | ")
                    .append(ScreeningCriteria.pct(LiveJudgeCriteria.intradayExposure(r, seed, setup.rules().zone())))
                    .append(" | ").append(ScreeningCriteria.pct(LiveJudgeCriteria.closeExposure(r))).append(" | ")
                    .append(mm == null ? "-" : mm.roundTripCount()).append(" | ")
                    .append(perTrip == null ? "-" : ScreeningCriteria.won(perTrip)).append(" | ").append(rejected).append(" |\n");
        }
        sb.append("\n- 벤치마크: 그 종목 매수 후 보유 수익률(비용 제외, 같은 기간 시장 방향 참고). 장중 노출: 거래일마다 09:00~15:30 동안 든 ")
                .append("포지션 매입 금액 × 시간 ÷ (시드머니 × 장 시간)의 평균(체결 시각 기준). 마감 노출: 거래일 마감 (평가금액 − 현금) ÷ ")
                .append("평가금액의 평균. 블랙리스트 거절: 리스크 검증이 블랙리스트 종목(")
                .append(black.isEmpty() ? "없음" : String.join(", ", black)).append(") 신호를 거절한 수.\n\n");

        sb.append("## 5. 지연 민감도(STR-13)\n\n");
        sb.append("| 프로필 | ");
        for (Lane l : lanes.latency()) {
            sb.append("지연 ").append(ScreeningCriteria.seconds(l.execution().orderLatency())).append("초 | ");
        }
        sb.append("기본 ").append(ScreeningCriteria.seconds(setup.execution().orderLatency())).append("초 |\n|---|");
        sb.append("---:|".repeat(lanes.latency().size() + 1)).append('\n');
        for (int k = 0; k < roles.judged().size(); k++) {
            RunReport b = base.runs().get(roles.judged().get(k));
            sb.append("| ").append(b.profile().id()).append(" | ");
            for (Lane l : lanes.latency()) {
                sb.append(retPf(byId.get(l.spec().id().value()).runs().get(k))).append(" | ");
            }
            sb.append(retPf(b)).append(" |\n");
        }
        sb.append("\n- 칸: 총수익률 / PF. L9는 2초 칸으로 판정한다.\n\n");

        sb.append("## 6. 무작위 대조군 분포(L10)\n\n");
        List<BigDecimal> nulls = new ArrayList<>();
        int failed = 0;
        int noTrips = 0;
        for (Lane l : lanes.nulls()) {
            RunReport r = byId.get(l.spec().id().value()).runs().get(0);
            BigDecimal v = LiveJudgeCriteria.perTrip(r, seed);
            if (v != null) {
                nulls.add(v);
            } else if (r.summary().metrics() == null) {
                failed++;
            } else {
                noTrips++;
            }
        }
        nulls.sort(BigDecimal::compareTo);
        sb.append("- 왕복당 순손익을 쓴 대조군 ").append(nulls.size()).append("개(실패 ").append(failed).append(", 왕복 0건 ")
                .append(noTrips).append(")\n");
        if (!nulls.isEmpty()) {
            sb.append("- 최소 ").append(ScreeningCriteria.won(nulls.get(0))).append("원, 중앙 ")
                    .append(ScreeningCriteria.won(ScreeningCriteria.quantile(nulls, new BigDecimal("0.5")))).append("원, 95분위 ")
                    .append(ScreeningCriteria.won(ScreeningCriteria.quantile(nulls, new BigDecimal("0.95")))).append("원, 최대 ")
                    .append(ScreeningCriteria.won(nulls.get(nulls.size() - 1))).append("원\n");
        }
        sb.append('\n');

        sb.append("## 7. 실시간 대 녹화(L11)\n\n");
        if (rec.report() == null) {
            sb.append("- 녹화 리플레이를 돌리지 못했습니다: ").append(rec.note()).append(". L11은 판정 불가다.\n");
        } else {
            sb.append("| 계좌 | 프로필 | 총수익률 실시간 | 총수익률 녹화 | 체결 실시간 | 체결 녹화 | 진입 일치율 | 체결가 괴리 평균 |\n");
            sb.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
            for (int i = 0; i < base.runs().size(); i++) {
                RunReport a = base.runs().get(i);
                RunReport b = rec.report().runs().get(i);
                RunMetrics ma = a.summary().metrics();
                RunMetrics mb = b.summary().metrics();
                LiveJudgeCriteria.TradeGap g = LiveJudgeCriteria.tradeGap(a, b);
                sb.append("| ").append(a.summary().accountId()).append(" | ").append(a.profile().id()).append(" | ")
                        .append(ma == null ? "-" : ScreeningCriteria.pct(ma.totalReturn())).append(" | ")
                        .append(mb == null ? "-" : ScreeningCriteria.pct(mb.totalReturn())).append(" | ")
                        .append(ma == null ? "-" : ma.fillCount()).append(" | ").append(mb == null ? "-" : mb.fillCount())
                        .append(" | ").append(g.entryMatch() == null ? "-" : ScreeningCriteria.pct(g.entryMatch()) + " ("
                                + g.matched() + "/" + g.liveEntries() + ")")
                        .append(" | ").append(g.priceGap() == null ? "-" : ScreeningCriteria.pct(g.priceGap())).append(" |\n");
            }
            sb.append("\n- 진입 일치율·체결가 괴리는 참고(D-066 4번 정의를 장중에 옮김: 진입(종목, 거래일) 중 녹화에도 있는 비율, 맞은 것의 ")
                    .append("첫 진입 체결가 차 평균). 판정은 D-114 L11(총수익률 차·체결 수 차)로만 한다.\n");
            sb.append("- 진입 일치는 (종목, 거래일)마다 첫 진입만 맞춘다. 하루에 같은 종목을 여러 번 드나드는 프로필은 일치율이 실제보다 ")
                    .append("높게 나올 수 있다.\n");
        }
        sb.append('\n');

        sb.append("## 8. 변형(L7)\n\n");
        for (int i : roles.judged()) {
            Pair p = lanes.perturbed().get(i);
            sb.append("- ").append(base.runs().get(i).profile().id()).append(": ")
                    .append(p.key() == null ? p.note() : p.key() + " ×0.8 = " + p.lowValue() + ", ×1.2 = " + p.highValue()
                            + (p.note().isEmpty() ? "" : " (" + p.note() + ")"))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String role(Roles roles, int i) {
        return i == roles.baseline() ? "기준선" : i == roles.control() ? "대조군" : "판정 대상";
    }

    private static String mark(LiveJudgeCriteria.Judgement j, String code) {
        for (Check c : j.checks()) {
            if (c.code().equals(code)) {
                return switch (c.outcome()) {
                    case PASS -> "○";
                    case FAIL -> "✕";
                    case NOT_AVAILABLE -> "?";
                };
            }
        }
        return "-";
    }

    private static String label(Check.Outcome o) {
        return switch (o) {
            case PASS -> "통과";
            case FAIL -> "탈락";
            case NOT_AVAILABLE -> "판정 불가";
        };
    }

    private static String retPf(RunReport r) {
        RunMetrics m = r.summary().metrics();
        return m == null ? "실패" : ScreeningCriteria.pct(m.totalReturn()) + " / " + ScreeningCriteria.num(m.profitFactor());
    }
}
