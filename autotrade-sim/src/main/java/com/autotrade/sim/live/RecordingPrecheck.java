package com.autotrade.sim.live;

import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.marketdata.recording.RecordingLayout;
import com.autotrade.selection.profile.SelectionProfile;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.RoundTrip;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.recording.RecordedMarketDataSet;
import com.autotrade.sim.recording.RecordingExperiment;
import com.autotrade.sim.recording.StreamedRecordingDataSet;
import com.autotrade.sim.run.CodeVersion;
import com.autotrade.sim.run.ExperimentStoppedException;
import com.autotrade.sim.run.OrderRejection;
import com.autotrade.sim.run.ReportWriter;
import com.autotrade.sim.run.RunGate;
import com.autotrade.sim.run.RunReport;
import com.autotrade.sim.run.SimulationExperimentRunner.Lane;
import com.autotrade.sim.run.SimulationReport;
import com.autotrade.sim.selection.IntradayTracker;
import com.autotrade.sim.selection.SelectionCandidates;
import com.autotrade.sim.selection.SessionSelections;
import com.autotrade.sim.study.ScreeningCriteria;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 녹화 사전 검사(ADR-014 5장 F4, 6단계 계획 4.2·4.3): 게이트웨이 녹화 위에서 LIVE02 후보 조합을 돌려 P0~P3과 대조군 품질·LIVE02
 * 대조군 시드 수 표를 {@code experiments/<실험ID>/}{@value #FILE}에 쓴다. 사전 검사 통과는 승격 근거가 아니라 LIVE02 등록 후보를
 * 거르는 단계다(계획 4.2).
 *
 * <p>입력은 녹화 실험과 같다: 녹화일을 날마다 다시 읽고({@link StreamedRecordingDataSet}, 기간과 상관없는 메모리, D-115·D-117),
 * 장전 선정은 일봉·공시 스냅샷으로 날마다 계산해 고정 파일·적용 기록을 만든 뒤 모든 레인이 같은 기록을 읽는다(D-124). 장중 순위
 * 선정은 레인마다 추적기를 돌린다(D-129). 돌리는 것(레인):
 * <ul>
 *   <li>BASE: 등록 계좌 전부, 기본 지연 0.5초</li>
 *   <li>대조군 시드: 판정 대상이 있는 묶음마다 등록 파일 {@code group-null-seeds}의 N개(시드 = 실험 시드 + i, 레인 ID는 녹화 판정과
 *   같은 규칙 {@link LiveJudgement#nullLanes})</li>
 * </ul>
 * 변형·지연·녹화 대 입력 기록 레인은 없다(P0~P3에 쓰지 않음). 레인 묶음 나누기·병렬은 녹화 판정과 같고 결과에 영향이 없다.
 *
 * <p>판정 비교와 대조군 품질 계산은 모두 정확 산술({@link BigDecimal}·정수, {@link PrecheckMath})이다. 결과 파일에는 실행 시간처럼
 * 매번 달라지는 값을 쓰지 않아 같은 등록 파일·녹화·스냅샷이면 바이트로 같다. 걸린 시간은 로그 끝 줄에만 쓴다.
 */
public final class RecordingPrecheck {

    /** 결과 파일 이름(등록 파일 옆). 저장소에 커밋한다. */
    public static final String FILE = "precheck.md";
    /** 입력 기록 파일 이름(녹화 실험 {@code inputs.yaml}과 같은 모양). 저장소에 커밋한다. */
    public static final String INPUTS_FILE = "inputs.yaml";
    /** 결과 표시. */
    static final String PASS = "통과";
    static final String FAIL = "탈락";
    static final String NOT_AVAILABLE = "판정 불가";
    static final String CANDIDATE = "통과(LIVE02 후보)";
    /** 프로필 한도 키(한도 도달 표). */
    static final String MAX_ENTRIES = "limits.max-entries-per-day";
    static final String MAX_POSITIONS = "sizing.max-positions";
    /** 구독 커버리지 알림 문턱(ADR-012 7장 되돌릴 조건, 계획 4.3). */
    static final BigDecimal COVERAGE_ALERT = new BigDecimal("0.5");

    private RecordingPrecheck() {
    }

    /**
     * @param verdicts 판정 대상 계좌 ID → 결과({@value #CANDIDATE}·{@value #FAIL}·{@value #NOT_AVAILABLE})
     * @param failedRuns 실패한 Run이 있음(BASE·대조군 시드 레인 어디든)
     * @param determinism 병렬 대 직렬 확인 결과({@code inputs.yaml}에 쓴 문구)
     * @param files 결과 폴더에 쓸 파일(상대 경로 → 내용)
     */
    public record Outcome(Map<String, String> verdicts, boolean failedRuns, String determinism, Map<String, String> files) {
        public Outcome {
            verdicts = Collections.unmodifiableMap(new LinkedHashMap<>(verdicts));
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }

        /** 병렬 대 직렬 확인이 다름. */
        public boolean nondeterministic() {
            return determinism.startsWith(DIFFERENT);
        }
    }

    static final String DIFFERENT = "다름";

    /** 등록 파일을 읽어 사전 검사를 하고 결과를 등록 파일 옆에 쓴다. 끝까지 계산했을 때만 쓴다. */
    public static Outcome runAndWrite(Path experimentDir, Path repoRoot, Path recordingsRoot, Path snapshotsRoot,
            CodeVersion code, boolean verifyDeterminism, Consumer<String> log, RunGate gate) {
        long started = System.nanoTime();
        Outcome out = run(experimentDir, repoRoot, recordingsRoot, snapshotsRoot, code, verifyDeterminism, log, gate,
                Runtime.getRuntime().maxMemory());
        RecordingExperiment.removeStaleSelections(experimentDir, out.files().keySet(), log);
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            Path target = experimentDir.resolve(f.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("결과를 쓸 수 없습니다: " + target, e);
            }
        }
        log.accept("작성: " + experimentDir.resolve(FILE) + ", " + experimentDir.resolve(INPUTS_FILE)
                + (out.files().size() > 2 ? " 외 선정 파일 " + (out.files().size() - 2) + "개" : ""));
        log.accept("걸린 시간 " + elapsed(Duration.ofNanos(System.nanoTime() - started)));
        return out;
    }

    /**
     * @param snapshotsRoot 수집 스냅샷 폴더. 장전 선정 조합 계좌가 있을 때만 읽는다(D-124)
     * @param verifyDeterminism true면 레인을 한 묶음으로 다시 돌려 {@value #FILE}이 바이트로 같은지 본다
     * @param maxHeap 선정 준비의 힙 검사에 쓸 최대 힙(바이트)
     * @throws IllegalArgumentException 입력이 맞지 않을 때(아무 Run도 시작하지 않음)
     * @throws ExperimentStoppedException 정지 확인에서 멈췄을 때(결과 없음)
     */
    static Outcome run(Path experimentDir, Path repoRoot, Path recordingsRoot, Path snapshotsRoot, CodeVersion code,
            boolean verifyDeterminism, Consumer<String> log, RunGate gate, long maxHeap) {
        Path file = experimentDir.resolve("experiment.yaml");
        PrecheckPlan plan;
        try {
            plan = PrecheckPlan.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("등록 파일을 읽을 수 없습니다: " + file, e);
        }
        String id = plan.recording().id().value();
        if (!id.equals(experimentDir.getFileName().toString())) {
            throw new IllegalArgumentException("폴더 이름과 실험 ID가 다릅니다: " + experimentDir.getFileName() + " ≠ " + id);
        }
        gate.check();
        LiveSetup setup = LiveSetup.of(plan.live(), repoRoot);
        JudgeRoles.Roles roles = roles(plan, setup);

        TreeSet<Symbol> symbols = new TreeSet<>();
        StreamedRecordingDataSet data = open(plan, setup, recordingsRoot, gate, log, symbols);
        requireSymbols(setup, symbols);
        gate.check();
        RecordingExperiment.SelectionStage stage = setup.hasSelections()
                ? RecordingExperiment.SelectionStage.prepare(plan.recording(), setup.selectionProfiles(), data,
                        setup.calendar(), snapshotsRoot, code, log, gate, maxHeap)
                : null;
        try {
            Lane base = new Lane(setup.spec(ClockMode.REPLAY, data.version()), setup.execution());
            Map<Integer, List<Lane>> nulls = LiveJudgement.nullLanes(setup, roles, data.version(), 0);
            List<Lane> all = new ArrayList<>();
            all.add(base);
            nulls.values().forEach(all::addAll);
            int count = Math.min(LiveJudgement.MAX_GROUPS, Math.max(1, Math.min(plan.recording().parallelism(),
                    gate.maxParallelRuns())));
            List<List<Lane>> groups = LiveJudgement.groups(all, count);
            try {
                gate.onExperimentsPlanned(groups.size() + (verifyDeterminism && groups.size() > 1 ? 1 : 0));
            } catch (RuntimeException e) {
                // 진행 보고 실패는 사전 검사를 멈추지 않는다
            }
            int runs = all.stream().mapToInt(l -> l.spec().combinations().size()).sum();
            log.accept("레인 " + all.size() + "개(Run " + runs + "개)를 " + groups.size() + "묶음으로 돌립니다");
            Path selectionsBase = stage == null ? null : stage.dir();
            Map<String, SimulationReport> byId = LiveJudgement.runGroups(setup, selectionsBase, data, groups, code,
                    id + "-PRECHECK", gate);
            gate.check();
            List<IntradayTracker.Ranked> intraday = LiveJudgement.intradayRanks(setup, data);
            List<SessionSelections.Applied> applied = stage == null ? List.of() : stage.applied();
            Lanes lanes = new Lanes(base, nulls);
            Evaluated ev = evaluate(plan, setup, roles, lanes, byId);
            String report = render(plan, setup, roles, code, data, lanes, byId, ev, applied, intraday);

            String determinism = "확인 안 함(--verify-determinism으로 확인)";
            if (verifyDeterminism && groups.size() == 1) {
                determinism = "확인 안 함(실제 묶음 수가 1이라 병렬 대 직렬 비교가 안 됨. -PmaxParallelRuns와 등록 파일 parallelism을 "
                        + "2 이상으로)";
                log.accept("결정론 확인 생략: 실제 묶음 수가 1입니다");
            } else if (verifyDeterminism) {
                log.accept("결정론 확인: 한 묶음으로 다시 실행");
                gate.check();
                Map<String, SimulationReport> again = LiveJudgement.runGroups(setup, selectionsBase, data,
                        LiveJudgement.groups(all, 1), code, id + "-PRECHECK-VERIFY", gate);
                String second = render(plan, setup, roles, code, data, lanes, again,
                        evaluate(plan, setup, roles, lanes, again), applied, intraday);
                determinism = second.equals(report) ? "같음(묶음 " + groups.size() + " vs 1, " + FILE + " 바이트 일치)"
                        : DIFFERENT + "(묶음 " + groups.size() + " vs 1)";
                if (!second.equals(report)) {
                    log.accept("결정론 확인 실패: 두 실행의 " + FILE + "이 다릅니다");
                }
            }

            SimulationReport baseReport = byId.get(base.spec().id().value());
            Map<String, String> files = new LinkedHashMap<>();
            files.put(INPUTS_FILE, RecordingExperiment.inputs(plan.recording(), data, symbols, setup.cost(),
                    setup.execution(), setup.blacklist(), baseReport, determinism, ReportWriter.render(baseReport), applied)
                    + RecordingExperiment.intradayInputs(intraday));
            files.put(FILE, report);
            if (stage != null) {
                files.putAll(stage.texts());
            }
            for (IntradayTracker.Ranked r : intraday) {
                files.put(SessionSelections.DIR + "/" + IntradayTracker.fileName(r), r.result().canonicalJson() + "\n");
            }
            boolean failed = byId.values().stream().flatMap(r -> r.runs().stream())
                    .anyMatch(r -> !r.summary().failureReason().isEmpty());
            return new Outcome(ev.verdicts(), failed, determinism, files);
        } finally {
            if (stage != null) {
                stage.close();
            }
        }
    }

    /**
     * 사전 검사 역할(ADR-014 5.1): 판정 명령과 같은 계산에 기준선 선택({@code baselineRequired = false}), 그리고 사전 검사만의
     * 거절. 프로필을 읽어야 아는 것만 여기서 본다(형식은 {@link PrecheckPlan}).
     */
    static JudgeRoles.Roles roles(PrecheckPlan plan, LiveSetup setup) {
        LivePlan live = plan.live();
        JudgeRoles.Roles roles = JudgeRoles.of(setup.combinations(), live.referenceAccounts(), live.groupNullSeeds(), false);
        for (int control : roles.nullControls()) {
            if (!roles.seedsByControl().containsKey(control)) {
                throw new IllegalArgumentException("사전 검사는 group-null-seeds를 대조군이 있는 모든 묶음에 적어야 합니다(기본값 없음, "
                        + "ADR-014 5.1): " + groupKey(setup, control) + " 묶음이 없습니다");
            }
        }
        if (plan.familySize() < roles.m()) {
            throw new IllegalArgumentException("family-size " + plan.familySize() + "가 실제 판정 대상 수 " + roles.m()
                    + "보다 작습니다(빠진 조합도 셉니다, 계획 4.1)");
        }
        for (String line : plan.p2()) {
            int i = plan.account(line);
            requireJudged(roles, i, "p2", line);
            Integer control = roles.controlOf().get(i);
            if (control == null) {
                throw new IllegalArgumentException("p2 계좌의 묶음에 대조군(logic: random)이 없습니다: " + line);
            }
            int n = roles.seedsFor(control, 0);
            if (n < plan.minPlusOne() - 1) {
                throw new IllegalArgumentException("p2 계좌 " + line + "의 묶음(" + groupKey(setup, control) + ") 대조군 시드 수 " + n
                        + "가 ⌈family-size ÷ alpha⌉ − 1 = " + (plan.minPlusOne() - 1) + "보다 작아 P2를 판정할 수 없습니다(ADR-014 5.1)");
            }
        }
        for (PrecheckPlan.Pair p : plan.p3()) {
            requireJudged(roles, plan.account(p.judged()), "p3 왼쪽", p.judged());
        }
        return roles;
    }

    private static void requireJudged(JudgeRoles.Roles roles, int i, String key, String line) {
        if (!roles.judged().contains(i)) {
            throw new IllegalArgumentException(key + " 계좌가 판정 대상이 아닙니다(기준선·대조군·참고 계좌는 판정하지 않음): " + line);
        }
    }

    private static String groupKey(LiveSetup setup, int account) {
        String key = setup.combinations().get(account).selectionKeyOrNone();
        return key.equals(com.autotrade.selection.profile.Combination.NONE) ? "선정 없음(none)" : "선정 " + key;
    }

    /** 기간 안 녹화일을 날짜순으로 연다. 녹화 실험과 같은 검사(휴장일 녹화, 주식 호가 단위)를 한다. */
    private static StreamedRecordingDataSet open(PrecheckPlan plan, LiveSetup setup, Path recordingsRoot, RunGate gate,
            Consumer<String> log, Set<Symbol> symbols) {
        LivePlan live = plan.live();
        RecordingLayout layout = new RecordingLayout(recordingsRoot.toAbsolutePath());
        List<Path> dirs = new ArrayList<>();
        for (LocalDate d = live.from(); !d.isAfter(live.to()); d = d.plusDays(1)) {
            Path dir = layout.dayDir(live.market(), d);
            if (Files.isRegularFile(dir.resolve(RecordingLayout.EVENTS_FILE))) {
                dirs.add(dir);
            }
        }
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("기간 안에 녹화가 없습니다: " + layout.root() + " " + live.market() + " " + live.from()
                    + " ~ " + live.to());
        }
        log.accept("녹화 읽기: " + layout.root() + " " + live.market() + " " + live.from() + " ~ " + live.to());
        StreamedRecordingDataSet data = StreamedRecordingDataSet.open(live.market(), dirs, gate::check, e -> {
            symbols.add(e.symbol());
            RecordingExperiment.offStockTick(setup.rules(), e).ifPresent(price -> {
                throw new IllegalArgumentException("주식 호가 단위에 맞지 않는 녹화 가격입니다: " + e.symbol().code() + " "
                        + price.toPlainString() + " (sequence " + e.sequence() + "). 녹화 실험과 같이 주식만 받습니다");
            });
        });
        for (RecordedMarketDataSet.DayInfo d : data.days()) {
            if (!setup.calendar().isTradingDay(d.date())) {
                throw new IllegalArgumentException("캘린더상 휴장일의 녹화입니다: " + d.date());
            }
            log.accept("녹화일 " + d.date() + ": 줄 " + d.recordedEvents() + ", 정상 종료 " + d.complete() + ", 잘림 "
                    + d.truncated() + ", 구간 " + d.segments() + ", 끼운 공백 " + d.insertedGaps());
        }
        return data;
    }

    /** 녹화 종목(녹화 실험과 같이 모두 boards에 있어야 하고, 벤치마크는 녹화에 있어야 한다). */
    private static void requireSymbols(LiveSetup setup, Set<Symbol> symbols) {
        for (Symbol s : symbols) {
            if (!setup.plan().boards().containsKey(s.code())) {
                throw new IllegalArgumentException("등록 파일 boards에 소속부가 없는 녹화 종목입니다: " + s.code());
            }
        }
        setup.plan().benchmark().ifPresent(b -> {
            if (!symbols.contains(Symbol.krx(b))) {
                throw new IllegalArgumentException("벤치마크 종목이 녹화에 없습니다: " + b);
            }
        });
    }

    /** 레인: BASE와 대조군 계좌 번호 → 그 묶음 시드 레인. */
    record Lanes(Lane base, Map<Integer, List<Lane>> nulls) {
    }

    /** 한 항목 결과. {@code mark}는 표 기호(○ 통과, ✕ 탈락, ? 판정 불가, – 걸리지 않음). */
    record Item(String outcome, String evidence) {
        static final Item NONE = new Item("–", "");

        String mark() {
            return switch (outcome) {
                case PASS -> "○";
                case FAIL -> "✕";
                case NOT_AVAILABLE -> "?";
                default -> outcome;
            };
        }
    }

    /** 계좌 하나의 P0~P3과 결과. 판정 대상이 아니면 결과는 "–". */
    record AccountResult(Item p0, Item p1, Item p2, Item p3, String result) {
    }

    /**
     * @param accounts 계좌 번호 → 결과(BASE 계좌 순서)
     * @param verdicts 판정 대상 계좌 ID → 결과
     * @param segments 계좌 번호 → 구간별 값(구간 순서)
     * @param crossing 계좌 번호 → 구간 경계를 넘긴 왕복 수
     */
    record Evaluated(Map<Integer, AccountResult> accounts, Map<String, String> verdicts,
            Map<Integer, List<SegmentValue>> segments, Map<Integer, Integer> crossing) {
    }

    /** 구간 값: 순손익(원), 왕복 수, 왕복당 순손익(왕복 0건이면 null). */
    record SegmentValue(BigDecimal net, int trips, BigDecimal perTrip) {
    }

    static Evaluated evaluate(PrecheckPlan plan, LiveSetup setup, JudgeRoles.Roles roles, Lanes lanes,
            Map<String, SimulationReport> byId) {
        BigDecimal seed = plan.live().seedCapital().amount();
        SimulationReport base = byId.get(lanes.base().spec().id().value());
        Map<Integer, List<BigDecimal>> distributions = new LinkedHashMap<>();
        lanes.nulls().forEach((control, nullLanes) -> distributions.put(control, usable(nullLanes, byId, seed)));
        Map<Integer, List<SegmentValue>> segments = new LinkedHashMap<>();
        Map<Integer, Integer> crossing = new LinkedHashMap<>();
        for (int i = 0; i < base.runs().size(); i++) {
            segments.put(i, segmentValues(base.runs().get(i), plan.segments(), seed));
            crossing.put(i, crossing(base.runs().get(i), plan.segments()));
        }
        Map<Integer, AccountResult> accounts = new LinkedHashMap<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        Set<Integer> p2 = new TreeSet<>(plan.p2().stream().map(plan::account).toList());
        for (int i = 0; i < base.runs().size(); i++) {
            RunReport r = base.runs().get(i);
            if (!roles.judged().contains(i)) {
                accounts.put(i, new AccountResult(Item.NONE, Item.NONE, Item.NONE, Item.NONE, "–"));
                continue;
            }
            Item p0 = p0(r, plan.minRoundTrips());
            Item p1 = p1(r, seed);
            List<BigDecimal> dist = distributions.getOrDefault(roles.controlOf().get(i), List.of());
            Item p2Item = p2(r, seed, dist, plan, p2.contains(i));
            List<Item> p3Items = new ArrayList<>();
            for (PrecheckPlan.Pair pair : plan.p3()) {
                if (plan.account(pair.judged()) == i) {
                    int ref = plan.account(pair.reference());
                    p3Items.add(p3(r, base.runs().get(ref), segments.get(i), segments.get(ref), plan, seed));
                }
            }
            Item p3Item = combine(p3Items);
            List<Item> applied = new ArrayList<>(List.of(p0, p1));
            if (p2.contains(i)) {
                applied.add(p2Item);
            }
            if (!p3Items.isEmpty()) {
                applied.add(p3Item);
            }
            String result = applied.stream().anyMatch(x -> x.outcome().equals(FAIL)) ? FAIL
                    : applied.stream().anyMatch(x -> x.outcome().equals(NOT_AVAILABLE)) ? NOT_AVAILABLE : CANDIDATE;
            accounts.put(i, new AccountResult(p0, p1, p2Item, p3Item, result));
            verdicts.put(r.summary().accountId().value(), result);
        }
        return new Evaluated(accounts, verdicts, segments, crossing);
    }

    /** 대조군 시드 레인들의 왕복당 순손익(왕복 0건·실패는 뺌), 오름차순. */
    private static List<BigDecimal> usable(List<Lane> nullLanes, Map<String, SimulationReport> byId, BigDecimal seed) {
        List<BigDecimal> out = new ArrayList<>();
        for (Lane l : nullLanes) {
            BigDecimal v = LiveJudgeCriteria.perTrip(byId.get(l.spec().id().value()).runs().get(0), seed);
            if (v != null) {
                out.add(v);
            }
        }
        out.sort(BigDecimal::compareTo);
        return out;
    }

    static Item p0(RunReport r, int min) {
        RunMetrics m = r.summary().metrics();
        if (m == null) {
            return new Item(NOT_AVAILABLE, "실패한 Run");
        }
        return new Item(m.roundTripCount() >= min ? PASS : FAIL, "왕복 " + m.roundTripCount() + "건, 기준 ≥ " + min);
    }

    static Item p1(RunReport r, BigDecimal seed) {
        RunMetrics m = r.summary().metrics();
        if (m == null) {
            return new Item(NOT_AVAILABLE, "실패한 Run");
        }
        BigDecimal v = LiveJudgeCriteria.perTrip(r, seed);
        if (v == null) {
            return new Item(NOT_AVAILABLE, "왕복 0건");
        }
        return new Item(v.signum() > 0 ? PASS : FAIL, "왕복당 순손익 " + ScreeningCriteria.won(v) + "원, 기준 > 0");
    }

    /**
     * P2: 같은 묶음 대조군 분포(N′개)에서 k = 그 계좌 값 이상인 시드 수. {@code (k+1)·family-size ≤ alpha·(N′+1)}이면 통과(정수·
     * {@link BigDecimal} 비교, architect M1). N′ + 1 &lt; ⌈family-size ÷ alpha⌉이면 판정 불가. {@code judged}가 아니면 p만 "(참고)".
     */
    static Item p2(RunReport r, BigDecimal seed, List<BigDecimal> dist, PrecheckPlan plan, boolean judged) {
        BigDecimal v = LiveJudgeCriteria.perTrip(r, seed);
        if (v == null) {
            return judged ? new Item(NOT_AVAILABLE, r.summary().metrics() == null ? "실패한 Run" : "왕복 0건") : Item.NONE;
        }
        int n = dist.size();
        long k = dist.stream().filter(x -> x.compareTo(v) >= 0).count();
        String p = n == 0 ? "-" : BigDecimal.valueOf(k + 1).divide(BigDecimal.valueOf(n + 1L), 5, RoundingMode.HALF_EVEN)
                .toPlainString();
        String evidence = "k = " + k + ", N′ = " + n + ", p = (k+1)/(N′+1) = " + p;
        if (!judged) {
            return new Item("–", evidence + " (참고)");
        }
        String outcome = p2Outcome(k, n, plan.familySize(), plan.alpha());
        return new Item(outcome, evidence + (outcome.equals(NOT_AVAILABLE)
                ? ", N′ + 1 < ⌈family-size ÷ alpha⌉ = " + plan.minPlusOne()
                : ", 문턱 alpha/family-size = " + plan.threshold()));
    }

    /**
     * P2 판정(정수·{@link BigDecimal}만): N′ + 1 &lt; ⌈family ÷ alpha⌉이면 판정 불가, (k+1)·family ≤ alpha·(N′+1)이면 통과(같음은
     * 통과), 아니면 탈락.
     */
    static String p2Outcome(long k, int n, int family, BigDecimal alpha) {
        int minPlusOne = BigDecimal.valueOf(family).divide(alpha, 0, RoundingMode.CEILING).intValueExact();
        if (n + 1 < minPlusOne) {
            return NOT_AVAILABLE;
        }
        return BigDecimal.valueOf((k + 1) * family).compareTo(alpha.multiply(BigDecimal.valueOf(n + 1L))) <= 0 ? PASS : FAIL;
    }

    /**
     * P3: 구간마다 d_s = (판정 대상 구간 왕복당) − (참고 계좌 같은 값), 어느 한쪽이 구간 왕복 0건이면 그 구간은 d &gt; 0이 아님. 전체
     * d(왕복당 순손익 차) &gt; 0이고 d_s &gt; 0인 구간 수 ≥ {@code p3-min-segments}이면 통과.
     */
    static Item p3(RunReport judged, RunReport reference, List<SegmentValue> js, List<SegmentValue> rs, PrecheckPlan plan,
            BigDecimal seed) {
        String vs = "vs " + reference.summary().accountId() + ": ";
        if (judged.summary().metrics() == null || reference.summary().metrics() == null) {
            return new Item(NOT_AVAILABLE, vs + "실패한 Run");
        }
        BigDecimal a = LiveJudgeCriteria.perTrip(judged, seed);
        BigDecimal b = LiveJudgeCriteria.perTrip(reference, seed);
        if (a == null || b == null) {
            return new Item(NOT_AVAILABLE, vs + "전체 기간 왕복 0건(" + (a == null ? "판정 대상" : "참고 계좌") + ")");
        }
        BigDecimal d = a.subtract(b);
        List<String> parts = new ArrayList<>();
        for (BigDecimal ds : segmentDifferences(js, rs)) {
            parts.add(ds == null ? "–" : ScreeningCriteria.won(ds));
        }
        int positive = positiveSegments(js, rs);
        boolean pass = d.signum() > 0 && positive >= plan.p3MinSegments();
        return new Item(pass ? PASS : FAIL, vs + "d = " + ScreeningCriteria.won(d) + "원, 구간 d_s = " + String.join(" / ", parts)
                + ", d_s > 0 구간 " + positive + "/" + plan.segments().size() + "(기준 ≥ " + plan.p3MinSegments() + ")");
    }

    /** 구간별 d_s(어느 한쪽이 구간 왕복 0건이면 null). */
    static List<BigDecimal> segmentDifferences(List<SegmentValue> js, List<SegmentValue> rs) {
        List<BigDecimal> out = new ArrayList<>();
        for (int s = 0; s < js.size(); s++) {
            SegmentValue x = js.get(s);
            SegmentValue y = rs.get(s);
            out.add(x.perTrip() == null || y.perTrip() == null ? null : x.perTrip().subtract(y.perTrip()));
        }
        return out;
    }

    /** d_s &gt; 0인 구간 수(왕복 0건 구간은 d &gt; 0이 아님). */
    static int positiveSegments(List<SegmentValue> js, List<SegmentValue> rs) {
        return (int) segmentDifferences(js, rs).stream().filter(d -> d != null && d.signum() > 0).count();
    }

    /** 한 계좌에 P3 쌍이 여럿이면: 탈락 하나라도 있으면 탈락, 아니면 판정 불가가 있으면 판정 불가. */
    private static Item combine(List<Item> items) {
        if (items.isEmpty()) {
            return Item.NONE;
        }
        String outcome = items.stream().anyMatch(x -> x.outcome().equals(FAIL)) ? FAIL
                : items.stream().anyMatch(x -> x.outcome().equals(NOT_AVAILABLE)) ? NOT_AVAILABLE : PASS;
        return new Item(outcome, String.join("; ", items.stream().map(Item::evidence).toList()));
    }

    /**
     * 구간 값. 구간 순손익 = 구간 마지막 거래일 일별 평가금액 − 직전 구간 마지막 거래일 일별 평가금액(첫 구간은 시드머니). 구간 왕복 =
     * 청산일이 구간 안인 왕복.
     */
    static List<SegmentValue> segmentValues(RunReport r, List<PrecheckPlan.Segment> segments, BigDecimal seed) {
        return segmentValues(r.equityCurve(), r.roundTrips(), segments, seed);
    }

    static List<SegmentValue> segmentValues(List<EquityPoint> curve, List<RoundTrip> roundTrips,
            List<PrecheckPlan.Segment> segments, BigDecimal seed) {
        List<SegmentValue> out = new ArrayList<>();
        BigDecimal previous = seed;
        for (PrecheckPlan.Segment s : segments) {
            BigDecimal end = dailyEquity(curve, s.to(), seed);
            BigDecimal net = end.subtract(previous);
            int trips = (int) roundTrips.stream().filter(t -> s.contains(t.exitDate())).count();
            out.add(new SegmentValue(net, trips, trips == 0 ? null
                    : net.divide(BigDecimal.valueOf(trips), MathContext.DECIMAL64)));
            previous = end;
        }
        return out;
    }

    /** 일별 평가금액: 그날 이전(그날 포함) 가장 늦은 {@link EquityPoint}. 점이 없으면 시드머니(architect m5). */
    static BigDecimal dailyEquity(List<EquityPoint> curve, LocalDate date, BigDecimal seed) {
        EquityPoint last = null;
        for (EquityPoint p : curve) {
            if (p.date().isAfter(date)) {
                continue;
            }
            if (last == null || p.date().isAfter(last.date())
                    || (p.date().equals(last.date()) && !p.ts().isBefore(last.ts()))) {
                last = p;
            }
        }
        return last == null ? seed : last.equity().amount();
    }

    /** 진입 구간과 청산 구간이 다른 왕복 수(장 마감 청산을 못 해 밤을 넘긴 포지션, strategy-researcher 참고). */
    static int crossing(RunReport r, List<PrecheckPlan.Segment> segments) {
        return crossing(r.roundTrips(), segments);
    }

    static int crossing(List<RoundTrip> roundTrips, List<PrecheckPlan.Segment> segments) {
        int n = 0;
        for (RoundTrip t : roundTrips) {
            if (segmentOf(segments, t.entryDate()) != segmentOf(segments, t.exitDate())) {
                n++;
            }
        }
        return n;
    }

    private static int segmentOf(List<PrecheckPlan.Segment> segments, LocalDate d) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).contains(d)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 한도 도달: 신규 진입 주문 수(그날 신규 진입 체결의 서로 다른 주문 키 수. 한 주문이 여러 체결로 나뉘어도 한 건,
     * {@code limits.max-entries-per-day}는 하루 진입 주문 수 상한이라서, PR #210 strategy-researcher M1)가 한도에 닿은 날 수, 체결
     * 직후 보유 종목 수가 한도에 닿은 날 수(체결 기준).
     */
    record Limits(String entryDays, String positionDays, Map<String, Integer> rejections) {
    }

    static Limits limits(RunReport r) {
        Integer maxEntries = intParam(r, MAX_ENTRIES);
        Integer maxPositions = intParam(r, MAX_POSITIONS);
        Map<LocalDate, Set<String>> entries = new TreeMap<>();
        Set<LocalDate> fullDays = new TreeSet<>();
        Map<String, Long> open = new HashMap<>();
        for (AppliedFill af : r.fills()) {
            Fill f = af.fill();
            if (f.intent() == OrderIntent.ENTRY) {
                entries.computeIfAbsent(f.exchangeDate(), d -> new TreeSet<>()).add(f.key().toString());
            }
            open.merge(f.symbol().code(), f.side() == Side.BUY ? f.quantity() : -f.quantity(), Long::sum);
            long held = open.values().stream().filter(q -> q > 0).count();
            if (maxPositions != null && held >= maxPositions) {
                fullDays.add(f.exchangeDate());
            }
        }
        String entryDays = maxEntries == null ? "–"
                : entries.values().stream().filter(keys -> keys.size() >= maxEntries).count() + "일(한도 " + maxEntries + ")";
        String positionDays = maxPositions == null ? "–" : fullDays.size() + "일(한도 " + maxPositions + ")";
        Map<String, Integer> rejections = new TreeMap<>();
        for (OrderRejection o : r.rejections()) {
            rejections.merge(o.code(), 1, Integer::sum);
        }
        return new Limits(entryDays, positionDays, rejections);
    }

    private static Integer intParam(RunReport r, String key) {
        String v = r.profile().parameters().get(key);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String render(PrecheckPlan plan, LiveSetup setup, JudgeRoles.Roles roles, CodeVersion code,
            StreamedRecordingDataSet data, Lanes lanes, Map<String, SimulationReport> byId, Evaluated ev,
            List<SessionSelections.Applied> applied, List<IntradayTracker.Ranked> intraday) {
        LivePlan live = plan.live();
        BigDecimal seed = live.seedCapital().amount();
        SimulationReport base = byId.get(lanes.base().spec().id().value());
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(live.id()).append(" 녹화 사전 검사(ADR-014 F4)\n\n");
        sb.append("> 사전 검사 통과는 승격 근거가 아니다(계획 4.2). LIVE02 등록 후보를 거르는 단계이고, 후보도 실시간 모의 LIVE02 ")
                .append("판정을 거친다. 명령 `runRecordingPrecheck`가 쓴다. 고치지 않는다(다시 돌리면 같은 내용이 나온다).\n");
        sb.append("> 비용 차감 성과만 쓴다. 이 결과는 투자 자문이 아니며, 실전 적용 여부는 사용자가 정한다.\n\n");

        sb.append("## 1. 머리말\n\n");
        sb.append("- 질문: ").append(live.question()).append('\n');
        sb.append("- 기간: ").append(live.from()).append(" ~ ").append(live.to()).append(", 녹화 ").append(data.days().size())
                .append("일(").append(data.days().get(0).date()).append(" ~ ")
                .append(data.days().get(data.days().size() - 1).date()).append("), 이벤트 ").append(data.eventCount())
                .append(", 시드 ").append(live.seed()).append(", 계좌별 시드머니 ").append(live.seedCapital()).append('\n');
        sb.append("- 코드: ").append(code.commit()).append(code.dirty() ? " dirty" : "").append('\n');
        sb.append("- 데이터 버전: ").append(data.version()).append('\n');
        sb.append("- 비용: ").append(setup.cost().id()).append(", 체결: ").append(setup.execution().id()).append('\n');
        int lanesCount = 1 + lanes.nulls().values().stream().mapToInt(List::size).sum();
        int runs = lanes.base().spec().combinations().size() + lanes.nulls().values().stream().mapToInt(List::size).sum();
        sb.append("- 돌린 것: 레인 ").append(lanesCount).append("개(Run ").append(runs).append("개) = BASE 1 + 대조군 시드 ");
        List<String> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<Lane>> e : lanes.nulls().entrySet()) {
            groups.add(groupKey(setup, e.getKey()) + " " + e.getValue().size());
        }
        sb.append(groups.isEmpty() ? "없음" : String.join(", ", groups)).append("(시드 = 실험 시드 + 1 ~ + N, 등록 파일 ")
                .append("`group-null-seeds`)\n");
        sb.append("- 판정 대상 ").append(roles.m()).append("개, m_pre = family-size ").append(plan.familySize())
                .append(", alpha ").append(plan.alpha().toPlainString()).append(", P2 문턱 alpha/family-size = ")
                .append(plan.threshold()).append(", P0 왕복 ≥ ").append(plan.minRoundTrips());
        if (!plan.p3().isEmpty()) {
            sb.append(", P3 d_s > 0 구간 ≥ ").append(plan.p3MinSegments());
        }
        sb.append('\n');
        sb.append("- 참고 계좌 ").append(roles.references().size()).append("개(판정하지 않음, 대조군 시드를 돌리지 않음, 등록 파일 ")
                .append("`references`)");
        if (roles.baseline() < 0) {
            sb.append(", 기준선 없음");
        }
        sb.append('\n');
        sb.append("- 구간: ").append(String.join(", ", plan.segments().stream().map(PrecheckPlan.Segment::toString).toList()))
                .append('\n');
        sb.append('\n');

        sb.append("## 2. 판정 요약\n\n");
        sb.append("> ○ 통과, ✕ 탈락, ? 판정 불가, – 걸리지 않은 항목. 결과는 걸린 P가 모두 통과면 ").append(CANDIDATE)
                .append(", 하나라도 탈락이면 탈락, 탈락 없이 판정 불가가 있으면 판정 불가.\n\n");
        sb.append("| 계좌 | 선정 x 전략 | 역할 | 결과 | P0 | P1 | P2 | P3 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < base.runs().size(); i++) {
            RunReport r = base.runs().get(i);
            AccountResult a = ev.accounts().get(i);
            sb.append("| ").append(r.summary().accountId()).append(" | ").append(label(r)).append(" | ").append(role(roles, i))
                    .append(" | ").append(a.result()).append(" | ").append(a.p0().mark()).append(" | ").append(a.p1().mark())
                    .append(" | ").append(a.p2().mark()).append(" | ").append(a.p3().mark()).append(" |\n");
        }
        sb.append('\n');

        sb.append("## 3. 항목별 근거\n\n");
        for (int i : roles.judged()) {
            RunReport r = base.runs().get(i);
            AccountResult a = ev.accounts().get(i);
            sb.append("### ").append(r.summary().accountId()).append(" ").append(label(r)).append(" — ").append(a.result())
                    .append("\n\n");
            sb.append("- P0 ").append(a.p0().outcome()).append(": ").append(a.p0().evidence()).append('\n');
            sb.append("- P1 ").append(a.p1().outcome()).append(": ").append(a.p1().evidence()).append('\n');
            sb.append("- P2 ").append(a.p2().outcome()).append(a.p2().evidence().isEmpty() ? "" : ": " + a.p2().evidence())
                    .append('\n');
            sb.append("- P3 ").append(a.p3().outcome()).append(a.p3().evidence().isEmpty() ? "" : ": " + a.p3().evidence())
                    .append('\n');
            sb.append('\n');
        }

        sb.append("## 4. 구간 표\n\n");
        sb.append("> 칸: 순손익(원) / 왕복 / 왕복당(원). 구간 순손익 = 구간 마지막 거래일 평가금액 − 직전 구간 마지막 거래일 평가금액")
                .append("(첫 구간은 시드머니). 구간 왕복은 청산일이 구간 안인 왕복. 경계 넘김 = 진입 구간과 청산 구간이 다른 왕복")
                .append("(손익과 왕복이 다른 구간에 들어갈 수 있음).\n\n");
        sb.append("| 계좌 | 역할 |");
        plan.segments().forEach(s -> sb.append(' ').append(s.from()).append(" ~ ").append(s.to()).append(" |"));
        sb.append(" 경계 넘김 |\n|---|---|");
        plan.segments().forEach(s -> sb.append("---:|"));
        sb.append("---:|\n");
        for (int i = 0; i < base.runs().size(); i++) {
            RunReport r = base.runs().get(i);
            sb.append("| ").append(r.summary().accountId()).append(" | ").append(role(roles, i)).append(" |");
            if (r.summary().metrics() == null) {
                plan.segments().forEach(s -> sb.append(" 실패 |"));
            } else {
                for (SegmentValue v : ev.segments().get(i)) {
                    sb.append(' ').append(ScreeningCriteria.won(v.net())).append(" / ").append(v.trips()).append(" / ")
                            .append(v.perTrip() == null ? "–" : ScreeningCriteria.won(v.perTrip())).append(" |");
                }
            }
            sb.append(' ').append(ev.crossing().get(i)).append(" |\n");
        }
        sb.append('\n');

        controlQuality(sb, setup, lanes, byId, seed);
        coverage(sb, setup, plan, applied);
        limitsSection(sb, roles, base);

        sb.append("## 8. 선정 표\n\n");
        if (!setup.hasSelections() && intraday.isEmpty()) {
            sb.append("선정 조합 계좌 없음\n");
        }
        if (setup.hasSelections()) {
            sb.append("### 장전 선정(D-124)\n\n");
            RecordingExperiment.selectionTable(sb, base, applied);
        }
        if (!intraday.isEmpty()) {
            sb.append(RecordingExperiment.intradayResultsTable(base, intraday)
                    .replace("\n## 장중 순위 선정(D-127)\n", "\n### 장중 순위 선정(D-127)\n"));
        }
        return sb.toString();
    }

    /** 5장: 묶음마다 쓸 수 없는 시드 비율, 단측 95% Clopper–Pearson 상한, LIVE02 대조군 시드 수 표. */
    private static void controlQuality(StringBuilder sb, LiveSetup setup, Lanes lanes, Map<String, SimulationReport> byId,
            BigDecimal seed) {
        sb.append("## 5. 대조군 품질·LIVE02 시드 수\n\n");
        if (lanes.nulls().isEmpty()) {
            sb.append("대조군 시드를 돌린 묶음 없음\n\n");
            return;
        }
        sb.append("> x = 쓸 수 없는 시드(왕복 0건 + 실패), N′ = N − x(P2 분포). 상한은 x/N의 단측 95% Clopper–Pearson 상한")
                .append("(정확 산술, 소수 4자리 올림). LIVE02 N은 판정 대상 m개일 때 N ≥ 21m − 1 중 쓸 수 있는 시드가 20m − 1보다 ")
                .append("모자랄 확률이 1% 이하인 가장 작은 N(계획 6.2 3번, 반올림 전 상한으로 셈). x/N ≥ 0.5인 묶음은 묶음 빼기")
                .append("(계획 6.2 1번), ").append(LivePlan.MAX_GROUP_NULL_SEEDS).append("를 넘으면 \"")
                .append(LivePlan.MAX_GROUP_NULL_SEEDS).append(" 초과\".\n\n");
        sb.append("| 묶음 | 대조군 | N | 왕복 0건 | 실패 | N′ | x/N | 95% 상한 |");
        for (int m = 1; m <= PrecheckMath.MAX_M; m++) {
            sb.append(" LIVE02 N(m = ").append(m).append(") |");
        }
        sb.append("\n|---|---|---:|---:|---:|---:|---:|---:|");
        for (int m = 1; m <= PrecheckMath.MAX_M; m++) {
            sb.append("---:|");
        }
        sb.append('\n');
        for (Map.Entry<Integer, List<Lane>> e : lanes.nulls().entrySet()) {
            int n = e.getValue().size();
            int zero = 0;
            int failed = 0;
            for (Lane l : e.getValue()) {
                RunMetrics m = byId.get(l.spec().id().value()).runs().get(0).summary().metrics();
                if (m == null) {
                    failed++;
                } else if (m.roundTripCount() == 0) {
                    zero++;
                }
            }
            int x = zero + failed;
            BigDecimal upper = PrecheckMath.upperBound(x, n);
            RunReport control = byId.get(lanes.base().spec().id().value()).runs().get(e.getKey());
            sb.append("| ").append(groupKey(setup, e.getKey())).append(" | ").append(control.summary().accountId())
                    .append(" | ").append(n).append(" | ").append(zero).append(" | ").append(failed).append(" | ")
                    .append(n - x).append(" | ")
                    .append(BigDecimal.valueOf(x).divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_EVEN).toPlainString())
                    .append(" | ").append(PrecheckMath.display(upper)).append(" |");
            boolean drop = 2L * x >= n;
            for (int m = 1; m <= PrecheckMath.MAX_M; m++) {
                if (drop) {
                    sb.append(" 묶음 빼기 |");
                    continue;
                }
                OptionalInt need = PrecheckMath.liveSeeds(upper, m, LivePlan.MAX_GROUP_NULL_SEEDS);
                sb.append(' ').append(need.isPresent() ? String.valueOf(need.getAsInt())
                        : LivePlan.MAX_GROUP_NULL_SEEDS + " 초과").append(" |");
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    /** 6장: 장전 선정 묶음마다 구독 커버리지(선정 ∩ boards ÷ 선정 수)의 구간 평균, 제외·빠진 날 수. */
    private static void coverage(StringBuilder sb, LiveSetup setup, PrecheckPlan plan,
            List<SessionSelections.Applied> applied) {
        sb.append("## 6. 구독 커버리지\n\n");
        if (!setup.hasSelections()) {
            sb.append("장전 선정 묶음 없음(장중 순위 선정은 boards 안 순위라 커버리지가 없다)\n\n");
            return;
        }
        sb.append("> 날마다 (선정 ∩ boards) ÷ 선정 수의 구간 평균(선정 0종목인 날과 적용하지 못한 날은 평균에서 뺌). ")
                .append("NOT_SUBSCRIBED·BLACKLISTED는 그 이유로 뺀 종목이 있던 날 수, SELECTION_MISSING은 적용하지 못한 날 수. ")
                .append("구간 평균들의 평균이 50% 미만이면 알림 줄을 쓴다(ADR-012 7장, 계획 4.3).\n\n");
        sb.append("| 선정 |");
        plan.segments().forEach(s -> sb.append(' ').append(s.from()).append(" ~ ").append(s.to()).append(" |"));
        sb.append(" 구간 평균 | NOT_SUBSCRIBED | BLACKLISTED | SELECTION_MISSING |\n|---|");
        plan.segments().forEach(s -> sb.append("---:|"));
        sb.append("---:|---:|---:|---:|\n");
        List<String> alerts = new ArrayList<>();
        for (SelectionProfile s : setup.selectionProfiles()) {
            int notSubscribed = 0;
            int blacklisted = 0;
            int missing = 0;
            List<List<BigDecimal>> bySegment = new ArrayList<>();
            plan.segments().forEach(x -> bySegment.add(new ArrayList<>()));
            for (SessionSelections.Applied a : applied) {
                SessionSelections.Item it = a.items().stream().filter(x -> x.selectionHash().equals(s.selectionHash()))
                        .findFirst().orElse(null);
                if (it == null || !it.applied()) {
                    missing++;
                    continue;
                }
                SelectionCandidates c = it.candidates();
                if (!c.excludedCodes(SelectionCandidates.Exclusion.NOT_SUBSCRIBED).isEmpty()) {
                    notSubscribed++;
                }
                if (!c.excludedCodes(SelectionCandidates.Exclusion.BLACKLISTED).isEmpty()) {
                    blacklisted++;
                }
                int seg = segmentOf(plan.segments(), a.date());
                if (c.selectedCount() > 0 && seg >= 0) {
                    bySegment.get(seg).add(BigDecimal.valueOf(c.onBoards())
                            .divide(BigDecimal.valueOf(c.selectedCount()), MathContext.DECIMAL64));
                }
            }
            sb.append("| ").append(s.key()).append(" |");
            List<BigDecimal> averages = new ArrayList<>();
            for (List<BigDecimal> values : bySegment) {
                BigDecimal avg = mean(values);
                if (avg != null) {
                    averages.add(avg);
                }
                sb.append(' ').append(avg == null ? "–" : ScreeningCriteria.pct(avg)).append(" |");
            }
            BigDecimal overall = mean(averages);
            sb.append(' ').append(overall == null ? "–" : ScreeningCriteria.pct(overall)).append(" | ").append(notSubscribed)
                    .append(" | ").append(blacklisted).append(" | ").append(missing).append(" |\n");
            if (overall != null && overall.compareTo(COVERAGE_ALERT) < 0) {
                alerts.add(s.key());
            }
        }
        sb.append('\n');
        for (String key : alerts) {
            sb.append("**ADR-012 7장 되돌릴 조건 알림**: ").append(key)
                    .append("의 구독 커버리지 구간 평균이 50% 미만이다(선정 종목 절반 넘게 boards 밖). 결과를 시장 전체 선정의 성과로 ")
                    .append("읽지 않는다.\n\n");
        }
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL64);
    }

    /** 7장: 판정 대상·참고 계좌마다 한도 도달 날 수와 거절 사유별 건수. */
    private static void limitsSection(StringBuilder sb, JudgeRoles.Roles roles, SimulationReport base) {
        sb.append("## 7. 한도 도달·거절 사유\n\n");
        sb.append("> 체결 기준이다: 그날 체결된 신규 진입 주문 수(한 주문의 나뉜 체결은 한 건)가 `").append(MAX_ENTRIES)
                .append("`에 닿은 날, 체결 직후 보유 종목 수가 `")
                .append(MAX_POSITIONS).append("`에 닿은 날. 신호가 한도에 막힌 날은 실행 결과에 기록이 없어 세지 못한다 `[추론]`. ")
                .append("한도 키가 없는 프로필은 –.\n\n");
        sb.append("| 계좌 | 역할 | 진입 한도 도달 | 보유 한도 도달 | 거절 사유(건) |\n|---|---|---:|---:|---|\n");
        List<Integer> shown = new ArrayList<>(roles.judged());
        shown.addAll(roles.references());
        Collections.sort(shown);
        for (int i : shown) {
            RunReport r = base.runs().get(i);
            Limits l = limits(r);
            List<String> reasons = new ArrayList<>();
            l.rejections().forEach((k, v) -> reasons.add(k + " " + v));
            sb.append("| ").append(r.summary().accountId()).append(" | ").append(role(roles, i)).append(" | ")
                    .append(l.entryDays()).append(" | ").append(l.positionDays()).append(" | ")
                    .append(reasons.isEmpty() ? "-" : String.join(", ", reasons)).append(" |\n");
        }
        sb.append('\n');
    }

    private static String label(RunReport r) {
        return (r.combination().hasSelection() ? r.combination().selectionKeyOrNone() + " x " : "") + r.profile().id() + "@"
                + r.profile().version();
    }

    private static String role(JudgeRoles.Roles roles, int i) {
        return i == roles.baseline() ? "기준선" : roles.controls().contains(i) ? "대조군"
                : roles.isReference(i) ? "참고" : "판정 대상";
    }

    static String elapsed(Duration d) {
        long s = d.getSeconds();
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
