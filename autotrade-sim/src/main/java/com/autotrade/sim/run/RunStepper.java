package com.autotrade.sim.run;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderAck;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.time.ReplayClock;
import com.autotrade.risk.RiskChain;
import com.autotrade.risk.RiskDecision;
import com.autotrade.selection.profile.Combination;
import com.autotrade.sim.ExperimentSpec;
import com.autotrade.sim.RunResult;
import com.autotrade.sim.RunStatus;
import com.autotrade.sim.account.VirtualAccount;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.exchange.ExecutionSettings;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.exchange.Fill;
import com.autotrade.sim.exchange.OrderRejectedReason;
import com.autotrade.sim.exchange.VirtualExchange;
import com.autotrade.sim.metrics.EquityPoint;
import com.autotrade.sim.metrics.MetricsCalculator;
import com.autotrade.sim.metrics.RunMetrics;
import com.autotrade.sim.selection.SelectionDay;
import com.autotrade.sim.selection.SelectionDays;
import com.autotrade.sim.selection.SelectionSchedule;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.TradeSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Run 하나(조합 하나 = 모의 계좌 하나)를 이벤트 하나씩 진행한다. 리플레이 실행기({@link SimulationExperimentRunner})와
 * 실시간 모의 세션({@code com.autotrade.sim.live.LiveSession}, ADR-010)이 이 클래스 하나를 같이 쓴다. 그래서 같은 이벤트를 같은
 * 순서로 주면 실시간이든 리플레이든 같은 결과가 나온다(SIM-13 비교의 전제, D-076).
 *
 * <p>이벤트 하나의 처리 순서는 {@link SimulationExperimentRunner} 클래스 주석(ADR-004 2.2)과 같다. 한 스레드에서만 쓴다.
 *
 * <p>쓰는 순서: 생성(예외 없음) → {@link #begin} → {@link #onEvent} 반복 → {@link #finish}. 중간에 런타임 예외가 나면
 * {@link #failed}로 실패 직전까지의 기록을 담은 결과를 만든다(계좌 격리: 그 Run만 실패).
 */
public final class RunStepper {

    private final ExperimentSpec spec;
    private final MarketRules rules;
    private final CostModel cost;
    private final ExecutionSettings execution;
    private final StrategyFactory strategies;
    private final RiskChain risk;
    private final boolean exchangeTimed;
    private final SelectionDays schedule;
    private final Combination combination;
    private final String runId;
    private final AccountId accountId;
    private final long runSeed;
    private final VirtualAccount account;
    private final RunState state = new RunState();

    private ReplayClock clock;
    private VirtualExchange exchange;
    private Strategy strategy;
    private SimOrderKeys keys;
    private LocalDate day;
    private Instant dayLastTime;

    /**
     * @param index 조합 위치(0부터). 계좌 번호({@code <실험ID>-A01}부터)와 Run 시드가 여기서 정해진다
     * @param exchangeTimed 데이터 이벤트 시각이 실제 거래소 시각인지({@link MarketDataSet#exchangeTimed()})
     * @param schedule 선정 있는 조합의 날짜별 선정 판정. 리플레이는 미리 만든 {@link SelectionSchedule}, 실시간 모의는 날짜 전환
     *     때 채우는 표(D-124). 선정 없으면 {@link SelectionSchedule#empty()}
     */
    public RunStepper(ExperimentSpec spec, int index, MarketRules rules, CostModel cost, ExecutionSettings execution,
            StrategyFactory strategies, RiskChain risk, boolean exchangeTimed, SelectionDays schedule) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.strategies = Objects.requireNonNull(strategies, "strategies");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.exchangeTimed = exchangeTimed;
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.combination = spec.combinations().get(index);
        this.runId = spec.id().value() + String.format("-A%02d", index + 1);
        this.accountId = new AccountId(runId);
        this.runSeed = RunSeeds.derive(spec.randomSeed(), combination);
        this.account = new VirtualAccount(accountId, spec.seedCapital());
    }

    /**
     * 시계·거래소·전략을 만들고 예열 이벤트를 전략에만 준다.
     *
     * @param start 시계 시작 시각. 리플레이는 첫 이벤트 수신 시각(없으면 실험 시작일 0시), 실시간도 첫 이벤트 수신 시각이다
     */
    public void begin(Instant start, List<MarketEvent> warmup) {
        clock = new ReplayClock(start, rules.zone());
        exchange = new VirtualExchange(accountId, rules, cost, execution, account, clock, exchangeTimed);
        strategy = strategies.create(combination.profile(), runSeed);
        keys = new SimOrderKeys();
        feedWarmup(warmup);
    }

    /**
     * 이벤트 하나를 처리한다. 거래일이 바뀌면 먼저 전날을 마감하고 {@code afterDayClose}를 부른다(리플레이 실행기의 정지 확인
     * 지점, D-063). 그 콜백이 던진 예외는 그대로 올라간다.
     */
    public void onEvent(MarketEvent e, Runnable afterDayClose) {
        LocalDate d = rules.tradingDate(e.exchangeTime());
        if (day != null && !d.equals(day)) {
            closeDay(day, dayLastTime);
            afterDayClose.run();
        }
        if (!d.equals(day) && combination.hasSelection()) {
            state.openSelectionDay(schedule.day(combination.selectionHashOrNone(), d));
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
            if (state.selectionDay != null && !s.intent().isProtective() && !state.selectionDay.allowsEntry(s.symbol())) {
                // 선정 밖 신규 진입은 주문을 만들지 않는다(멱등 키도 쓰지 않음). 건수만 그날 기록에 남긴다
                state.blockedToday++;
                continue;
            }
            submit(s, d, e);
        }
    }

    /** 마지막 거래일을 마감하고 지표를 계산한다. */
    public RunReport finish(BigDecimal benchmarkReturn, boolean settleAtWindowEnd) {
        Set<String> endLots = new HashSet<>();
        if (day != null) {
            if (settleAtWindowEnd) {
                settleAtEnd(day, dayLastTime, endLots);
            }
            closeDay(day, dayLastTime);
        }
        state.closeSelectionDay();
        RunMetrics metrics = MetricsCalculator.compute(spec.seedCapital(), spec.from(), spec.to(), state.equity,
                account.ledger(), account.roundTrips(), benchmarkReturn);
        RunResult summary = new RunResult(runId, accountId, combination.profile().profileHash(),
                combination.selectionHashOrNone(), combination.combinationHash(), runSeed, RunSeeds.scheme(combination),
                RunStatus.COMPLETED, metrics.finalEquity(), "", metrics);
        return new RunReport(summary, combination, account.ledger(), state.equity, account.roundTrips(),
                state.rejections, state.selectionDays, endLots);
    }

    /** 실패 직전까지의 기록을 담은 실패 결과. */
    public RunReport failed(RuntimeException e) {
        state.closeSelectionDay();
        RunResult summary = new RunResult(runId, accountId, combination.profile().profileHash(),
                combination.selectionHashOrNone(), combination.combinationHash(), runSeed, RunSeeds.scheme(combination),
                RunStatus.FAILED, null, failureReason(e), null);
        return new RunReport(summary, combination, account.ledger(), state.equity, account.roundTrips(),
                state.rejections, state.selectionDays);
    }

    public String runId() {
        return runId;
    }

    public Combination combination() {
        return combination;
    }

    /**
     * 화면용 현재 상태(실시간 모의). 결과에는 쓰지 않는다. {@link #begin} 전이면 시드머니 그대로다.
     *
     * @return 현금, 평가 자산(마지막 체결가 기준), 보유 종목 수, 체결 수, 거절 수
     */
    public Progress progress() {
        Money cash = account.cash();
        Money equity = exchange == null ? cash : account.equity(exchange::markPrice);
        return new Progress(runId, cash, equity, account.quantities().size(), account.ledger().size(),
                state.rejections.size(), day);
    }

    /**
     * 화면용 체결 원장 사본(실시간 모의 계좌 상세, D-120). 결과에는 쓰지 않는다. 오래된 것부터.
     */
    public List<AppliedFill> fillsSnapshot() {
        return List.copyOf(account.ledger());
    }

    /** 화면용 거절·취소 기록 사본(실시간 모의 계좌 상세, D-120). 결과에는 쓰지 않는다. 오래된 것부터. */
    public List<OrderRejection> rejectionsSnapshot() {
        return List.copyOf(state.rejections);
    }

    /** 화면용 현재 상태. {@code tradingDay}는 마지막으로 처리한 이벤트의 거래일(없으면 null). */
    public record Progress(String runId, Money cash, Money equity, int positions, int fills, int rejections,
            LocalDate tradingDay) {}

    static String failureReason(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "");
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /**
     * 예열 이벤트를 전략에만 준다. 전략에는 현금 0의 빈 계좌를 보여 줘 신호가 수량 0으로 사라지고(내부 진입 한도·예약 상태가
     * 쌓이지 않는다), 나온 신호는 어떤 경우에도 버린다. 거래소·계좌·리스크 체인·자산 곡선은 건드리지 않는다.
     */
    private void feedWarmup(List<MarketEvent> warmup) {
        if (warmup.isEmpty()) {
            return;
        }
        Money none = Money.zero(spec.seedCapital().currency());
        for (MarketEvent e : warmup) {
            AccountSnapshot empty = new AccountSnapshot(accountId, e.receivedAt(), none, new java.util.TreeMap<>());
            strategy.onEvent(e, new WarmupContext(e.receivedAt(), empty));
        }
    }

    private void submit(TradeSignal s, LocalDate d, MarketEvent e) {
        IdempotencyKey key = keys.next(d, s.symbol(), s.side());
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

    private void closeDay(LocalDate d, Instant lastTime) {
        exchange.endOfDay();
        for (OrderRequest r : state.submitted) {
            OrderRejectedReason reason = exchange.reasonOf(r.key());
            if (reason == OrderRejectedReason.DAY_END || reason == OrderRejectedReason.CASH_SHORT_AT_FILL) {
                state.rejections.add(new OrderRejection(state.lastSeq, r.key(), r.symbol(), "EXCHANGE", reason.name(), ""));
            }
        }
        state.submitted.clear();
        Money equity = account.equity(exchange::markPrice);
        state.equity.add(new EquityPoint(d, lastTime, equity, account.cash()));
        account.verifyInvariants();
    }

    /** 구간 끝 정리({@code settleAtWindowEnd}): 남은 포지션을 마지막 체결가 − 슬리피지(호가 단위 내림)에 팔고 비용을 뺀다. */
    private void settleAtEnd(LocalDate d, Instant lastTime, Set<String> endLots) {
        AccountSnapshot snap = account.snapshot(lastTime);
        for (Map.Entry<Symbol, Long> q : account.quantities().entrySet()) {
            Symbol s = q.getKey();
            long qty = q.getValue();
            BigDecimal mark = exchange.markPrice(s);
            if (mark == null) {
                mark = snap.positions().get(s).averagePrice().amount();
            }
            BigDecimal price = rules.roundToTick(s, mark.multiply(BigDecimal.ONE.subtract(cost.slippageRate())),
                    java.math.RoundingMode.FLOOR);
            if (price.signum() <= 0) {
                price = mark;
            }
            Money slippage = new Money(mark.subtract(price).multiply(BigDecimal.valueOf(qty)), account.currency())
                    .rounded(4, java.math.RoundingMode.HALF_EVEN);
            Fill fill = new Fill(keys.next(d, s, com.autotrade.domain.order.Side.SELL), 1, s,
                    com.autotrade.domain.order.Side.SELL, OrderIntent.EXIT, qty, price,
                    cost.fees(com.autotrade.domain.order.Side.SELL, s, d, price, qty), slippage, state.lastSeq,
                    state.lastSeq + 1, lastTime, lastTime, d);
            endLots.add(account.apply(fill).lotId());
        }
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

    private record WarmupContext(Instant now, AccountSnapshot account) implements StrategyContext {
        @Override
        public boolean hasOpenOrder(Symbol symbol) {
            return false;
        }
    }
}
