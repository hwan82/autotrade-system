package com.autotrade.sim.run;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderAck;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.time.ReplayClock;
import com.autotrade.risk.RiskChain;
import com.autotrade.risk.RiskDecision;
import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.ClockMode;
import com.autotrade.sim.ExperimentResult;
import com.autotrade.sim.ExperimentRunner;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.VirtualAccount;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.exchange.OrderRejectedReason;
import com.autotrade.sim.exchange.VirtualExchange;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.MetricsCalculator;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.selection.SelectionDay;
import com.autotrade.sim.selection.SelectionSchedule;
import com.autotrade.sim.selection.SelectionSource;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.TradeSignal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 녹화·스냅샷 리플레이 실험 실행기(SIM-03·04·09·10, {@link ExperimentRunner} 계약).
 *
 * <p>Run마다(= 조합마다) 계좌, 거래소, 시계, 전략, 멱등 키 발급기를 새로 만들고 아무것도 공유하지 않는다.
 * 공유하는 것은 불변 입력(이벤트 목록, 시장 규칙, 비용 모델, 날짜별 선정 결과 표)과 스레드 안전 계약을 가진 팩토리·리스크 정책뿐이다.
 * 그래서 병렬 실행 스레드 수가 결과에 영향을 주지 않는다.
 *
 * <p>이벤트 하나의 처리 순서(ADR-004 2.2)
 * <ol>
 *   <li>거래일이 바뀌면 전날 마감: 남은 주문 취소, 자산 곡선 한 점, 계좌 불변식 검사(SIM-15)</li>
 *   <li>시계를 이벤트 수신 시각으로 옮김</li>
 *   <li>가상 거래소가 이 이벤트로 기존 주문을 체결(신호를 낸 이벤트로는 체결하지 않음)</li>
 *   <li>전략이 이 이벤트까지 본 상태로 신호를 냄</li>
 *   <li>선정 있는 조합이면 보호 주문이 아닌 신호(신규 진입, {@link OrderIntent#isProtective()} 허용 목록 밖) 중 그날
 *       선정 결과 밖 종목, 또는 그날 결과가 없을 때의 신호를 버림(청산·손절은 그대로. STR-17·19, ADR-007 2.6 "선정 결과는
 *       후보일 뿐"). "그날"은 <b>신호를 낸 이벤트의 거래일</b>이다. 장 마감 직전 신호가 다음 거래일에 체결돼도 신호일의
 *       선정으로 거른다. 실전 엔진이 거래일마다 장전에 결과를 고정하고 그날 신호에 쓰는 것(ADR-007 2.6)과 같은 기준이다</li>
 *   <li>신호마다 멱등 키 발급 → 리스크 검증({@link RiskChain}: 블랙리스트·수동 보유 → 진입 규칙, 실전과 같은 코드) → 거래소 접수</li>
 * </ol>
 */
public final class SimulationExperimentRunner implements ExperimentRunner {

    private final MarketDataSet data;
    private final MarketRules rules;
    private final CostModel cost;
    private final StrategyFactory strategies;
    private final RiskChain risk;
    private final ExecutionSettings execution;
    private final CodeVersion code;
    private final int parallelism;
    private final RunGate gate;
    private final Symbol benchmark;
    private final SelectionSource selections;

    /**
     * 실험 하나의 조합 수 상한(SIM-14 실험실 자원 상한, STR-17 "조합 수가 상한을 넘으면 시작 거부") `[가정]`. 조합마다 계좌·
     * 거래소·전략 상태를 따로 두므로 메모리가 조합 수에 비례한다. 설정 키({@code lab.max-combinations})로 옮기는 것은 후속
     * (config-keys.md를 고치는 열린 PR #39 머지 뒤).
     */
    public static final int MAX_COMBINATIONS = 128;

    /**
     * @param risk 모의 주문이 통과할 검증 체인. 실전과 같은 {@link RiskChain}만 받는다(SIM-12). 승인만 하는 정책이나
     *     블랙리스트를 건너뛰는 정책은 넣을 수 없다. 리플레이는 실험 시작 때 찍은 블랙리스트 스냅샷 하나로 끝까지 돈다(RSK-12)
     * @param parallelism 동시에 돌릴 Run 수(lab.max-parallel-runs). 결과에는 영향 없음
     * @param benchmark 매수 후 보유 비교 종목. 없으면 null
     */
    public SimulationExperimentRunner(
            MarketDataSet data, MarketRules rules, CostModel cost, StrategyFactory strategies, RiskChain risk,
            ExecutionSettings execution, CodeVersion code, int parallelism, Symbol benchmark) {
        this(data, rules, cost, strategies, risk, execution, code, parallelism, benchmark, null);
    }

    /**
     * @param selections 선정 있는 조합의 날짜별 선정 결과 공급자. 선정 없는 실험만 돌리면 null이어도 된다
     */
    public SimulationExperimentRunner(
            MarketDataSet data, MarketRules rules, CostModel cost, StrategyFactory strategies, RiskChain risk,
            ExecutionSettings execution, CodeVersion code, int parallelism, Symbol benchmark,
            SelectionSource selections) {
        this(data, rules, cost, strategies, risk, execution, code, parallelism, benchmark, selections, RunGate.NONE);
    }

    /**
     * @param gate Run 시작 전과 Run 안에서 거래일이 바뀔 때마다 묻는 정지 확인(D-063). 멈추라고 하면 남은 Run을 시작하지
     *     않고 {@link ExperimentStoppedException}을 던진다. 결과에는 영향이 없다
     */
    public SimulationExperimentRunner(
            MarketDataSet data, MarketRules rules, CostModel cost, StrategyFactory strategies, RiskChain risk,
            ExecutionSettings execution, CodeVersion code, int parallelism, Symbol benchmark,
            SelectionSource selections, RunGate gate) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.data = Objects.requireNonNull(data, "data");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.strategies = Objects.requireNonNull(strategies, "strategies");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.code = Objects.requireNonNull(code, "code");
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("parallelism은 1~64여야 합니다: " + parallelism);
        }
        this.parallelism = parallelism;
        this.benchmark = benchmark;
        this.selections = selections;
    }

    @Override
    public ExperimentResult run(ExperimentSpec spec) {
        return runDetailed(spec).result();
    }

    /**
     * 실험을 돌리고 Run별 상세까지 돌려준다. 입력이 실험과 맞지 않으면(데이터 버전, 비용 모델 ID, 시장, 비용 표 범위)
     * 아무 Run도 시작하지 않고 {@link IllegalArgumentException}을 던진다.
     */
    public SimulationReport runDetailed(ExperimentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        List<MarketEvent> events = validateAndSlice(spec);
        BigDecimal benchmarkReturn = benchmarkReturn(events);
        gate.check(); // 선정 일정 계산(모든 선정 × 거래일) 전에 본다. 계산 중에도 공급자 호출마다 본다(PR #52 QA F1)
        SelectionSchedule schedule = selectionSchedule(spec, events);
        List<Combination> combinations = spec.combinations();
        List<RunReport> reports = new ArrayList<>(combinations.size());
        // 한 Run이 멈춤을 보면 나머지는 문을 다시 묻지 않고 다음 확인 지점에서 같은 예외로 끝난다(D-063). 어느 Run의 결과를
        // 먼저 받든 처음 받은 정지 이유(장 시간, 정지 요청 파일 등)가 명령까지 올라가야 해서 예외 자체를 남긴다(PR #52 architect R1)
        AtomicReference<ExperimentStoppedException> stopped = new AtomicReference<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, combinations.size()))) {
            List<Future<RunReport>> futures = new ArrayList<>();
            for (int i = 0; i < combinations.size(); i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    checkGate(stopped);
                    return runOne(spec, index, events, benchmarkReturn, schedule, stopped);
                }));
            }
            try {
                for (Future<RunReport> f : futures) {
                    reports.add(f.get());
                }
            } catch (ExecutionException e) {
                futures.forEach(f -> f.cancel(false)); // 아직 시작하지 않은 Run은 시작하지 않는다
                if (e.getCause() instanceof ExperimentStoppedException s) {
                    stopped.compareAndSet(null, s);
                    throw stopped.get();
                }
                // runOne은 Run 실패를 결과로 담으므로 여기 오는 것은 실행기 자체 오류다
                throw new IllegalStateException("실험 실행기 오류: " + spec.id(), e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실험 실행이 중단됐습니다: " + spec.id(), e);
        }
        List<RunResult> summaries = reports.stream().map(RunReport::summary).toList();
        ExperimentResult result = new ExperimentResult(spec.id(), code.commit(), code.dirty(),
                cost.tableHash(spec.from(), spec.to()), execution.id(), summaries);
        return new SimulationReport(result, reports);
    }

    private List<MarketEvent> validateAndSlice(ExperimentSpec spec) {
        if (spec.combinations().size() > MAX_COMBINATIONS) {
            throw new IllegalArgumentException("조합 수가 실험실 상한을 넘습니다(SIM-14): " + spec.combinations().size()
                    + " > " + MAX_COMBINATIONS);
        }
        if (spec.hasSelections() && selections == null) {
            throw new IllegalArgumentException("선정 있는 조합이 있는데 선정 결과 공급자가 없습니다: " + spec.id());
        }
        if (spec.clockMode() != ClockMode.REPLAY) {
            throw new IllegalArgumentException("이 실행기는 리플레이만 돌립니다. 실시간 모의는 6단계 작업입니다: " + spec.clockMode());
        }
        if (!spec.dataSnapshotVersion().equals(data.version())) {
            throw new IllegalArgumentException(
                    "데이터 스냅샷이 실험과 다릅니다: " + spec.dataSnapshotVersion() + " ≠ " + data.version());
        }
        if (!spec.costModelId().equals(cost.id())) {
            throw new IllegalArgumentException("비용 모델 ID가 실험과 다릅니다: " + spec.costModelId() + " ≠ " + cost.id());
        }
        var market = spec.market();
        if (market != rules.market() || market != cost.market() || market != data.market()) {
            throw new IllegalArgumentException("실험·시장 규칙·비용 모델·데이터의 시장이 다릅니다");
        }
        if (!cost.covers(spec.from(), spec.to())) {
            throw new IllegalArgumentException(
                    "실험 기간이 비용 표 밖입니다(SIM-07): " + spec.from() + " ~ " + spec.to() + ", " + cost.id());
        }
        // 기간 안 이벤트가 이어져 있으면(거래일이 sequence 순으로 느는 보통의 경우) 복사하지 않고 그 구간만 넘긴다.
        // 일봉 데이터셋(DailyBarMarketDataSet)은 이벤트를 읽을 때 만들므로 복사하면 메모리가 이벤트 수에 비례해진다.
        // 이어져 있지 않으면 예전처럼 기간 안 이벤트만 모은다. 어느 쪽이든 Run이 보는 이벤트 순서와 내용은 같다.
        List<MarketEvent> all = data.events();
        int first = -1;
        int last = -1;
        boolean contiguous = true;
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < all.size(); i++) {
            MarketEvent e = all.get(i);
            if (e.sequence() <= prev) {
                throw new IllegalArgumentException("시세 이벤트가 sequence 오름차순이 아닙니다: " + e.sequence());
            }
            prev = e.sequence();
            LocalDate d = rules.tradingDate(e.exchangeTime());
            if (!d.isBefore(spec.from()) && !d.isAfter(spec.to())) {
                if (first < 0) {
                    first = i;
                } else if (last != i - 1) {
                    contiguous = false;
                }
                last = i;
            }
        }
        if (first < 0) {
            return List.of();
        }
        if (contiguous && all instanceof RandomAccess) {
            return Collections.unmodifiableList(all.subList(first, last + 1));
        }
        List<MarketEvent> out = new ArrayList<>();
        for (MarketEvent e : all) {
            LocalDate d = rules.tradingDate(e.exchangeTime());
            if (!d.isBefore(spec.from()) && !d.isAfter(spec.to())) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 선정 해시 × 거래일마다 공급자를 한 번만 불러 Run들이 공유할 표를 만든다(STR-17). Run 시작 전, 호출 스레드에서 거래일
     * 오름차순으로 부르므로 병렬 Run 수와 무관하게 같은 표가 나온다.
     */
    private SelectionSchedule selectionSchedule(ExperimentSpec spec, List<MarketEvent> events) {
        if (!spec.hasSelections()) {
            return SelectionSchedule.empty();
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (MarketEvent e : events) {
            dates.add(rules.tradingDate(e.exchangeTime()));
        }
        return SelectionSchedule.compute(spec.combinations(), dates, selections, gate::check);
    }

    private BigDecimal benchmarkReturn(List<MarketEvent> events) {
        if (benchmark == null) {
            return null;
        }
        BigDecimal first = null;
        BigDecimal last = null;
        for (MarketEvent e : events) {
            if (e instanceof TradeTick t && t.symbol().equals(benchmark)) {
                if (first == null) {
                    first = t.price();
                }
                last = t.price();
            }
        }
        return first == null ? null : last.divide(first, MathContext.DECIMAL128).subtract(BigDecimal.ONE);
    }

    private void checkGate(AtomicReference<ExperimentStoppedException> stopped) {
        ExperimentStoppedException first = stopped.get();
        if (first != null) {
            throw first;
        }
        try {
            gate.check();
        } catch (ExperimentStoppedException e) {
            stopped.compareAndSet(null, e);
            throw stopped.get();
        }
    }

    private RunReport runOne(
            ExperimentSpec spec, int index, List<MarketEvent> events, BigDecimal benchmarkReturn,
            SelectionSchedule schedule, AtomicReference<ExperimentStoppedException> stopped) {
        Combination combination = spec.combinations().get(index);
        String runId = spec.id().value() + String.format("-A%02d", index + 1);
        AccountId accountId = new AccountId(runId);
        String profileHash = combination.profile().profileHash();
        String selectionHash = combination.selectionHashOrNone();
        String combinationHash = combination.combinationHash();
        long runSeed = RunSeeds.derive(spec.randomSeed(), combination);
        String seedScheme = RunSeeds.scheme(combination);
        VirtualAccount account = new VirtualAccount(accountId, spec.seedCapital());
        RunState state = new RunState();
        try {
            Instant start = events.isEmpty() ? spec.from().atStartOfDay(rules.zone()).toInstant()
                    : events.get(0).receivedAt();
            ReplayClock clock = new ReplayClock(start, rules.zone());
            VirtualExchange exchange = new VirtualExchange(accountId, rules, cost, execution, account, clock);
            Strategy strategy = strategies.create(combination.profile(), runSeed);
            SimOrderKeys keys = new SimOrderKeys();
            LocalDate day = null;
            Instant dayLastTime = null;
            for (MarketEvent e : events) {
                LocalDate d = rules.tradingDate(e.exchangeTime());
                if (day != null && !d.equals(day)) {
                    closeDay(day, dayLastTime, exchange, account, state);
                    checkGate(stopped);
                }
                if (!d.equals(day) && combination.hasSelection()) {
                    state.openSelectionDay(schedule.day(selectionHash, d));
                }
                day = d;
                dayLastTime = e.exchangeTime();
                clock.advanceToAtLeast(e.receivedAt());
                exchange.onEvent(e);
                state.lastSeq = e.sequence();
                Instant now = clock.instant();
                AccountSnapshot snapshot = account.snapshot(now);
                List<TradeSignal> signals = strategy.onEvent(e, new Context(now, snapshot, exchange));
                for (TradeSignal s : signals) {
                    if (state.selectionDay != null && !s.intent().isProtective()
                            && !state.selectionDay.allowsEntry(s.symbol())) {
                        // 선정 밖 신규 진입은 주문을 만들지 않는다(멱등 키도 쓰지 않음). 건수만 그날 기록에 남긴다
                        state.blockedToday++;
                        continue;
                    }
                    submit(s, d, e, accountId, keys, exchange, account, clock, state);
                }
            }
            if (day != null) {
                closeDay(day, dayLastTime, exchange, account, state);
            }
            state.closeSelectionDay();
            RunMetrics metrics = MetricsCalculator.compute(spec.seedCapital(), spec.from(), spec.to(), state.equity,
                    account.ledger(), account.roundTrips(), benchmarkReturn);
            RunResult summary = new RunResult(runId, accountId, profileHash, selectionHash, combinationHash, runSeed,
                    seedScheme, RunStatus.COMPLETED, metrics.finalEquity(), "", metrics);
            return new RunReport(summary, combination, account.ledger(), state.equity, account.roundTrips(),
                    state.rejections, state.selectionDays);
        } catch (ExperimentStoppedException e) {
            throw e; // Run 실패가 아니라 실험 전체 정지다(결과에 남기지 않음)
        } catch (RuntimeException e) {
            // 계좌 격리: 이 Run만 실패로 끝낸다. 다른 Run은 계속 돈다
            state.closeSelectionDay();
            RunResult summary = new RunResult(runId, accountId, profileHash, selectionHash, combinationHash, runSeed,
                    seedScheme, RunStatus.FAILED, null, failureReason(e), null);
            return new RunReport(summary, combination, account.ledger(), state.equity, account.roundTrips(),
                    state.rejections, state.selectionDays);
        }
    }

    private void submit(
            TradeSignal s, LocalDate day, MarketEvent e, AccountId accountId, SimOrderKeys keys,
            VirtualExchange exchange, VirtualAccount account, ReplayClock clock, RunState state) {
        IdempotencyKey key = keys.next(day, s.symbol(), s.side());
        OrderRequest request = new OrderRequest(key, accountId, s.symbol(), s.side(), s.intent(), s.type(),
                s.limitPrice(), s.quantity());
        RiskDecision decision = risk.evaluate(request, account.snapshot(clock.instant()));
        if (decision instanceof RiskDecision.Rejected r) {
            state.rejections.add(new OrderRejection(e.sequence(), key, s.symbol(), "RISK", r.ruleId(), r.reason()));
            return;
        }
        OrderAck ack = exchange.submit(request);
        if (ack.state() == OrderState.REJECTED) {
            state.rejections.add(new OrderRejection(e.sequence(), key, s.symbol(), "EXCHANGE", ack.message(), ""));
        }
        state.submitted.add(request);
    }

    private void closeDay(
            LocalDate day, Instant lastTime, VirtualExchange exchange, VirtualAccount account, RunState state) {
        exchange.endOfDay();
        for (OrderRequest r : state.submitted) {
            OrderRejectedReason reason = exchange.reasonOf(r.key());
            if (reason == OrderRejectedReason.DAY_END || reason == OrderRejectedReason.CASH_SHORT_AT_FILL) {
                state.rejections.add(new OrderRejection(state.lastSeq, r.key(), r.symbol(), "EXCHANGE", reason.name(), ""));
            }
        }
        state.submitted.clear();
        Money equity = account.equity(exchange::markPrice);
        state.equity.add(new EquityPoint(day, lastTime, equity, account.cash()));
        account.verifyInvariants();
    }

    private static String failureReason(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "");
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** Run 하나의 기록용 가변 상태. */
    private static final class RunState {
        private final List<EquityPoint> equity = new ArrayList<>();
        private final List<OrderRejection> rejections = new ArrayList<>();
        /** 이번 거래일에 접수한 주문(마감 시 취소 사유 기록용). 접수 순서. */
        private final List<OrderRequest> submitted = new ArrayList<>();
        /** 마지막으로 처리한 이벤트 순번. 마감 취소 기록의 시점이다. */
        private long lastSeq;
        /** 선정 있는 조합의 날짜별 기록. 거래일 오름차순. */
        private final List<RunSelectionDay> selectionDays = new ArrayList<>();
        /** 오늘 쓰는 선정 판정. 선정 없는 조합이면 null(신규 진입을 거르지 않음). */
        private SelectionDay selectionDay;
        private int blockedToday;

        void openSelectionDay(SelectionDay today) {
            closeSelectionDay();
            selectionDay = today;
            blockedToday = 0;
        }

        void closeSelectionDay() {
            if (selectionDay != null) {
                selectionDays.add(new RunSelectionDay(selectionDay, blockedToday));
                selectionDay = null;
            }
        }
    }

    private record Context(Instant now, AccountSnapshot account, VirtualExchange exchange) implements StrategyContext {
        @Override
        public boolean hasOpenOrder(Symbol symbol) {
            return exchange.hasOpenOrder(symbol);
        }
    }
}
