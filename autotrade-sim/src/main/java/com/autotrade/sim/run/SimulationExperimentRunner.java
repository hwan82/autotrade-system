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
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.time.ReplayClock;
import com.autotrade.risk.RiskDecision;
import com.autotrade.risk.RiskPolicy;
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
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradeSignal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 녹화·스냅샷 리플레이 실험 실행기(SIM-03·04·09·10, {@link ExperimentRunner} 계약).
 *
 * <p>Run마다(= 프로필마다) 계좌, 거래소, 시계, 전략, 멱등 키 발급기를 새로 만들고 아무것도 공유하지 않는다.
 * 공유하는 것은 불변 입력(이벤트 목록, 시장 규칙, 비용 모델)과 스레드 안전 계약을 가진 팩토리·리스크 정책뿐이다.
 * 그래서 병렬 실행 스레드 수가 결과에 영향을 주지 않는다.
 *
 * <p>이벤트 하나의 처리 순서(ADR-004 2.2)
 * <ol>
 *   <li>거래일이 바뀌면 전날 마감: 남은 주문 취소, 자산 곡선 한 점, 계좌 불변식 검사(SIM-15)</li>
 *   <li>시계를 이벤트 수신 시각으로 옮김</li>
 *   <li>가상 거래소가 이 이벤트로 기존 주문을 체결(신호를 낸 이벤트로는 체결하지 않음)</li>
 *   <li>전략이 이 이벤트까지 본 상태로 신호를 냄</li>
 *   <li>신호마다 멱등 키 발급 → 리스크 검증({@link RiskPolicy}, 블랙리스트 포함) → 거래소 접수</li>
 * </ol>
 */
public final class SimulationExperimentRunner implements ExperimentRunner {

    private final MarketDataSet data;
    private final MarketRules rules;
    private final CostModel cost;
    private final StrategyFactory strategies;
    private final RiskPolicy risk;
    private final ExecutionSettings execution;
    private final CodeVersion code;
    private final int parallelism;
    private final Symbol benchmark;

    /**
     * @param risk 모의 주문이 통과할 검증 체인. 실전과 같은 구현을 넣는다(SIM-12). 체인 구성은 risk-ops 작업
     * @param parallelism 동시에 돌릴 Run 수(lab.max-parallel-runs). 결과에는 영향 없음
     * @param benchmark 매수 후 보유 비교 종목. 없으면 null
     */
    public SimulationExperimentRunner(
            MarketDataSet data, MarketRules rules, CostModel cost, StrategyFactory strategies, RiskPolicy risk,
            ExecutionSettings execution, CodeVersion code, int parallelism, Symbol benchmark) {
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
        List<StrategyProfile> profiles = spec.profiles();
        List<RunReport> reports = new ArrayList<>(profiles.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, profiles.size()))) {
            List<Future<RunReport>> futures = new ArrayList<>();
            for (int i = 0; i < profiles.size(); i++) {
                int index = i;
                futures.add(pool.submit(() -> runOne(spec, index, events, benchmarkReturn)));
            }
            for (Future<RunReport> f : futures) {
                reports.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("실험 실행이 중단됐습니다: " + spec.id(), e);
        } catch (ExecutionException e) {
            // runOne은 Run 실패를 결과로 담으므로 여기 오는 것은 실행기 자체 오류다
            throw new IllegalStateException("실험 실행기 오류: " + spec.id(), e.getCause());
        }
        List<RunResult> summaries = reports.stream().map(RunReport::summary).toList();
        ExperimentResult result = new ExperimentResult(spec.id(), code.commit(), code.dirty(),
                cost.tableHash(spec.from(), spec.to()), execution.id(), summaries);
        return new SimulationReport(result, reports);
    }

    private List<MarketEvent> validateAndSlice(ExperimentSpec spec) {
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
        var market = spec.profiles().get(0).market();
        if (market != rules.market() || market != cost.market() || market != data.market()) {
            throw new IllegalArgumentException("실험·시장 규칙·비용 모델·데이터의 시장이 다릅니다");
        }
        if (!cost.covers(spec.from(), spec.to())) {
            throw new IllegalArgumentException(
                    "실험 기간이 비용 표 밖입니다(SIM-07): " + spec.from() + " ~ " + spec.to() + ", " + cost.id());
        }
        List<MarketEvent> out = new ArrayList<>();
        long prev = Long.MIN_VALUE;
        for (MarketEvent e : data.events()) {
            if (e.sequence() <= prev) {
                throw new IllegalArgumentException("시세 이벤트가 sequence 오름차순이 아닙니다: " + e.sequence());
            }
            prev = e.sequence();
            LocalDate d = rules.tradingDate(e.exchangeTime());
            if (!d.isBefore(spec.from()) && !d.isAfter(spec.to())) {
                out.add(e);
            }
        }
        return List.copyOf(out);
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

    private RunReport runOne(ExperimentSpec spec, int index, List<MarketEvent> events, BigDecimal benchmarkReturn) {
        StrategyProfile profile = spec.profiles().get(index);
        String runId = spec.id().value() + String.format("-A%02d", index + 1);
        AccountId accountId = new AccountId(runId);
        String profileHash = profile.profileHash();
        long runSeed = RunSeeds.derive(spec.randomSeed(), profileHash);
        VirtualAccount account = new VirtualAccount(accountId, spec.seedCapital());
        RunState state = new RunState();
        try {
            Instant start = events.isEmpty() ? spec.from().atStartOfDay(rules.zone()).toInstant()
                    : events.get(0).receivedAt();
            ReplayClock clock = new ReplayClock(start, rules.zone());
            VirtualExchange exchange = new VirtualExchange(accountId, rules, cost, execution, account, clock);
            Strategy strategy = strategies.create(profile, runSeed);
            SimOrderKeys keys = new SimOrderKeys();
            LocalDate day = null;
            Instant dayLastTime = null;
            for (MarketEvent e : events) {
                LocalDate d = rules.tradingDate(e.exchangeTime());
                if (day != null && !d.equals(day)) {
                    closeDay(day, dayLastTime, exchange, account, state);
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
                    submit(s, d, e, accountId, keys, exchange, account, clock, state);
                }
            }
            if (day != null) {
                closeDay(day, dayLastTime, exchange, account, state);
            }
            RunMetrics metrics = MetricsCalculator.compute(spec.seedCapital(), spec.from(), spec.to(), state.equity,
                    account.ledger(), account.roundTrips(), benchmarkReturn);
            RunResult summary = new RunResult(runId, accountId, profileHash, runSeed, RunStatus.COMPLETED,
                    metrics.finalEquity(), "", metrics);
            return new RunReport(summary, profile, account.ledger(), state.equity, account.roundTrips(), state.rejections);
        } catch (RuntimeException e) {
            // 계좌 격리: 이 Run만 실패로 끝낸다. 다른 Run은 계속 돈다
            RunResult summary = new RunResult(runId, accountId, profileHash, runSeed, RunStatus.FAILED, null,
                    failureReason(e), null);
            return new RunReport(summary, profile, account.ledger(), state.equity, account.roundTrips(), state.rejections);
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
    }

    private record Context(Instant now, AccountSnapshot account, VirtualExchange exchange) implements StrategyContext {
        @Override
        public boolean hasOpenOrder(Symbol symbol) {
            return exchange.hasOpenOrder(symbol);
        }
    }
}
