package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.account.Position;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.Symbol;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.TradeSignal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 진입 로직 하나 + 공통 매매 규칙({@link TradingRules})으로 된 전략. 하위 클래스는 "언제 사고 싶은가"(주 신호)만 정하고,
 * 신호 만료·확인 신호(STR-07), 비중과 한도(STR-12), 청산(STR-09)은 여기서 같은 코드로 처리한다. 그래서 프로필끼리
 * 성과 차이는 진입 로직과 파라미터 차이에서만 나온다.
 *
 * <p>이벤트 하나의 처리 순서(그 이벤트의 종목만 다룬다)
 * <ol>
 *   <li>거래일이 바뀌면 당일 카운터(진입 수, 연속 손절, 진입 중단)를 비우고 당일 시작 평가금액을 잡는다</li>
 *   <li>종목 상태 갱신 후 {@link #observe} 호출(지표 상태는 항상 갱신)</li>
 *   <li>보유 중이면 청산 조건 확인. 손절 → 익절 → 추적 손절 → 시간 → 최대 보유일 → 로직 청산 → 장 마감 순으로 첫 사유 하나로 전량 시장가 매도</li>
 *   <li>보유가 없으면 주 신호 확인 → 확인 신호를 만료 전까지 기다림 → 한도 확인 → 수량 계산 → 시장가 매수</li>
 * </ol>
 * 주문은 모두 시장가다. 신호(T) 다음 이벤트부터 체결되는 것은 실행기·가상 거래소가 보장한다.
 *
 * <p>평가금액은 현금 + Σ 보유 수량 × 종목 마지막 체결가(없으면 평균 단가)로 추정한다. 미체결 매수는 현금에서 빠지지 않은
 * 값이 오므로({@link StrategyContext#account()}) 이 전략이 낸 미체결 진입 금액을 따로 빼고 계산한다.
 */
public abstract class ManagedStrategy implements Strategy {

    /** 수량 계산 때 비용·호가 변동 여유. 매수 비용(수수료 0.015%)보다 넉넉하게 둔다 `[가정]`. */
    private static final BigDecimal COST_MARGIN = new BigDecimal("1.003");
    /** 청산 주문을 다시 내기 전 기다리는 시간(거절·취소 후 매 이벤트 재전송 방지). */
    private static final Duration EXIT_RETRY = Duration.ofSeconds(5);

    protected final TradingRules rules;
    protected final MarketRules market;
    private final Map<Symbol, SymbolState> states = new TreeMap<>();
    private final Map<Symbol, Instant> armedAt = new HashMap<>();
    private final Map<Symbol, Instant> openedAt = new HashMap<>();
    private final Map<Symbol, BigDecimal> peak = new HashMap<>();
    private final Map<Symbol, Instant> lastExitAt = new HashMap<>();
    private final Map<Symbol, ExitReason> pendingExit = new HashMap<>();
    private final Map<Symbol, Instant> exitSentAt = new HashMap<>();
    private final Map<Symbol, BigDecimal> pendingEntryNotional = new HashMap<>();
    /** 보유를 처음 본 거래일 번호(최대 보유일 계산, STR-18). */
    private final Map<Symbol, Integer> openedDay = new HashMap<>();

    private LocalDate today;
    /** 이 전략이 이벤트를 받은 거래일 수 − 1. 리플레이·실시간 모두 거래일마다 이벤트가 오므로 거래일 번호로 쓴다. */
    private int dayIndex = -1;
    private int entriesToday;
    private int consecutiveStops;
    private boolean haltedToday;
    private BigDecimal dayStartEquity;
    private final EntryFunnel funnel = new EntryFunnel();
    private final ExitLog exitLog = new ExitLog();

    protected ManagedStrategy(TradingRules rules, MarketRules market) {
        this.rules = rules;
        this.market = market;
    }

    /** 이 종목의 모든 이벤트마다 불린다(진입 가능 여부와 무관). 지표 상태 갱신용. */
    protected void observe(SymbolState s, MarketEvent event, LocalTime time) {
    }

    /** 새 거래일 첫 이벤트에서 불린다. 하루 한 번 신호 같은 플래그를 비운다. */
    protected void onNewDay(SymbolState s) {
    }

    /**
     * 주 신호: 보유가 없고 진입 허용 시간 안일 때 이벤트마다 불린다. true면 신호가 켜지고(arm), 확인 신호와 한도를 통과하면
     * 매수한다. 한 번 켜진 신호는 만료되거나 주문이 나갈 때까지 유지된다.
     */
    protected abstract boolean trigger(SymbolState s, MarketEvent event, LocalTime time);

    /**
     * 첫 판단 전에 받아야 하는 끝난 거래일 수(예열 필요량). 이보다 적은 거래일만 받은 동안은 신호를 내지 않는다. 기본은 0
     * (지난 거래일이 필요 없음). 워크포워드는 등록 파일의 예열 일수가 이 값보다 작으면 시작하지 않는다(ADR-009 2.3).
     */
    protected int requiredHistoryDays() {
        return 0;
    }

    /** 이 전략의 진입 깔때기 측정. Run이 끝난 뒤 읽는다(판정에는 쓰지 않음). */
    public final EntryFunnel entryFunnel() {
        return funnel;
    }

    /** 이 전략의 청산 사유 기록. Run이 끝난 뒤 읽는다(판정에는 쓰지 않음). */
    public final ExitLog exitLog() {
        return exitLog;
    }

    /** 로직 청산 신호(예: 추세 이탈). 기본은 없음. */
    protected boolean logicExit(SymbolState s, MarketEvent event, LocalTime time) {
        return false;
    }

    @Override
    public final List<TradeSignal> onEvent(MarketEvent event, StrategyContext ctx) {
        Symbol symbol = event.symbol();
        ZonedDateTime local = event.exchangeTime().atZone(market.zone());
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        AccountSnapshot account = ctx.account();
        Instant now = ctx.now();
        if (!date.equals(today)) {
            today = date;
            dayIndex++;
            entriesToday = 0;
            consecutiveStops = 0;
            haltedToday = false;
            dayStartEquity = equity(account);
            funnel.day(date);
        }
        SymbolState s = states.computeIfAbsent(symbol, SymbolState::new);
        if (s.roll(date)) {
            onNewDay(s);
        }
        s.update(event, time);
        observe(s, event, time);

        long held = account.quantityOf(symbol);
        boolean open = ctx.hasOpenOrder(symbol);
        if (!open) {
            pendingEntryNotional.remove(symbol);
        }
        trackPosition(symbol, held, s, now);
        if (held > 0) {
            armedAt.remove(symbol);
            return exit(s, event, time, now, held, open, account);
        }
        if (open) {
            return List.of();
        }
        return entry(s, event, time, now, account, ctx);
    }

    private void trackPosition(Symbol symbol, long held, SymbolState s, Instant now) {
        if (held > 0) {
            openedAt.putIfAbsent(symbol, now);
            openedDay.putIfAbsent(symbol, dayIndex);
            if (s.lastPrice() != null) {
                peak.merge(symbol, s.lastPrice(), BigDecimal::max);
            }
        } else if (openedAt.remove(symbol) != null) {
            openedDay.remove(symbol);
            // 포지션이 막 닫혔다: 청산 사유를 연속 손절 계산에 반영
            peak.remove(symbol);
            exitSentAt.remove(symbol);
            lastExitAt.put(symbol, now);
            ExitReason reason = pendingExit.remove(symbol);
            if (reason == ExitReason.STOP_LOSS) {
                if (++consecutiveStops >= rules.maxConsecutiveStops()) {
                    haltedToday = true;
                }
            } else if (reason != null) {
                consecutiveStops = 0;
            }
        }
    }

    private List<TradeSignal> exit(
            SymbolState s, MarketEvent event, LocalTime time, Instant now, long held, boolean open,
            AccountSnapshot account) {
        Symbol symbol = s.symbol();
        if (open) {
            return List.of();
        }
        Instant sent = exitSentAt.get(symbol);
        if (sent != null && Duration.between(sent, now).compareTo(EXIT_RETRY) < 0) {
            return List.of();
        }
        BigDecimal last = s.lastPrice();
        ExitReason reason = null;
        if (last != null) {
            BigDecimal avg = account.positions().get(symbol).averagePrice().amount();
            if (last.compareTo(avg.multiply(BigDecimal.ONE.subtract(rules.stopLoss()))) <= 0) {
                reason = ExitReason.STOP_LOSS;
            } else if (rules.takeProfit().signum() > 0
                    && last.compareTo(avg.multiply(BigDecimal.ONE.add(rules.takeProfit()))) >= 0) {
                reason = ExitReason.TAKE_PROFIT;
            } else if (rules.trailing().signum() > 0 && peak.containsKey(symbol)
                    && last.compareTo(peak.get(symbol).multiply(BigDecimal.ONE.subtract(rules.trailing()))) <= 0) {
                reason = ExitReason.TRAILING_STOP;
            }
        }
        if (reason == null && !rules.timeExit().isZero()
                && Duration.between(openedAt.get(symbol), now).compareTo(rules.timeExit()) >= 0) {
            reason = ExitReason.TIME_EXIT;
        }
        if (reason == null && rules.maxHoldDays() > 0 && dayIndex - openedDay.get(symbol) >= rules.maxHoldDays()) {
            reason = ExitReason.TIME_EXIT; // 최대 보유일(STR-18): 보유 거래일 수가 상한에 이른 날 첫 이벤트
        }
        if (reason == null && logicExit(s, event, time)) {
            reason = ExitReason.LOGIC_EXIT;
        }
        if (reason == null && rules.eod() == EodPolicy.FLATTEN && !time.isBefore(rules.eodTime())) {
            reason = ExitReason.END_OF_DAY;
        }
        if (reason == null) {
            return List.of();
        }
        pendingExit.put(symbol, reason);
        exitSentAt.put(symbol, now);
        exitLog.sent(symbol, today, reason);
        return List.of(TradeSignal.marketSell(symbol, reason.intent(), held));
    }

    private List<TradeSignal> entry(
            SymbolState s, MarketEvent event, LocalTime time, Instant now, AccountSnapshot account,
            StrategyContext ctx) {
        Symbol symbol = s.symbol();
        boolean inWindow = !time.isBefore(rules.entryStart()) && time.isBefore(rules.entryEnd());
        if (!inWindow) {
            armedAt.remove(symbol);
            return List.of();
        }
        if (!armedAt.containsKey(symbol) && trigger(s, event, time)) {
            armedAt.put(symbol, now);
        }
        Instant armed = armedAt.get(symbol);
        if (armed == null) {
            return List.of();
        }
        if (Duration.between(armed, now).compareTo(rules.signalExpiry()) > 0) {
            armedAt.remove(symbol); // 만료(STR-07): 만료된 신호로는 주문하지 않는다
            return List.of();
        }
        if (!confirmed(s, time)) {
            return List.of();
        }
        armedAt.remove(symbol);
        funnel.signal(today);
        if (!allowedByLimits(symbol, now, account, ctx)) {
            funnel.limitBlocked(today);
            return List.of();
        }
        BigDecimal price = s.entryReference();
        if (price == null || price.compareTo(rules.minPrice()) < 0) {
            funnel.sizeBlocked(today);
            return List.of();
        }
        long qty = quantity(price, account);
        if (qty < market.quantityStep()) {
            funnel.sizeBlocked(today);
            return List.of();
        }
        entriesToday++;
        funnel.entry(today);
        pendingEntryNotional.put(symbol, price.multiply(BigDecimal.valueOf(qty)));
        return List.of(TradeSignal.marketBuy(symbol, qty));
    }

    private boolean confirmed(SymbolState s, LocalTime time) {
        if (rules.confirmVolumeRatio().signum() == 0) {
            return true;
        }
        BigDecimal ratio = s.volumeRatio(time, rules.confirmWindowMinutes());
        return ratio != null && ratio.compareTo(rules.confirmVolumeRatio()) >= 0;
    }

    private boolean allowedByLimits(Symbol symbol, Instant now, AccountSnapshot account, StrategyContext ctx) {
        if (haltedToday || entriesToday >= rules.maxEntriesPerDay()) {
            return false;
        }
        Instant exited = lastExitAt.get(symbol);
        if (exited != null && Duration.between(exited, now).compareTo(rules.reentryCooldown()) < 0) {
            return false;
        }
        int slots = account.positions().size();
        for (Symbol pending : pendingEntryNotional.keySet()) {
            if (account.quantityOf(pending) == 0 && ctx.hasOpenOrder(pending)) {
                slots++;
            }
        }
        if (slots >= rules.maxPositions()) {
            return false;
        }
        BigDecimal equity = equity(account);
        if (dayStartEquity != null && dayStartEquity.signum() > 0) {
            BigDecimal change = equity.subtract(dayStartEquity).divide(dayStartEquity, MathContext.DECIMAL64);
            if (change.compareTo(rules.dailyLossLimit().negate()) <= 0) {
                haltedToday = true;
                return false;
            }
        }
        return true;
    }

    /** 거래당 위험·종목당 상한·현금 여유 중 가장 작은 수량(STR-12). 수량 단위로 내림. */
    private long quantity(BigDecimal price, AccountSnapshot account) {
        BigDecimal equity = equity(account);
        BigDecimal byRisk = equity.multiply(rules.riskPerTrade())
                .divide(price.multiply(rules.stopLoss()), 0, RoundingMode.DOWN);
        BigDecimal byWeight = equity.multiply(rules.maxPositionWeight()).divide(price, 0, RoundingMode.DOWN);
        BigDecimal pending = pendingEntryNotional.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spendable = account.cash().amount().subtract(equity.multiply(rules.cashBuffer())).subtract(pending);
        if (spendable.signum() <= 0) {
            return 0;
        }
        BigDecimal byCash = spendable.divide(price.multiply(COST_MARGIN), 0, RoundingMode.DOWN);
        long qty = byRisk.min(byWeight).min(byCash).longValueExact();
        long step = market.quantityStep();
        return qty - qty % step;
    }

    /** 평가금액 추정: 현금 + 보유 × 마지막 체결가(없으면 평균 단가). */
    protected BigDecimal equity(AccountSnapshot account) {
        Money total = account.cash();
        BigDecimal sum = total.amount();
        for (Position p : account.positions().values()) {
            SymbolState s = states.get(p.symbol());
            BigDecimal mark = s != null && s.lastPrice() != null ? s.lastPrice() : p.averagePrice().amount();
            sum = sum.add(mark.multiply(BigDecimal.valueOf(p.quantity())));
        }
        return sum;
    }

    /** 테스트·진단용: 지금까지 본 종목 상태. */
    protected final Map<Symbol, SymbolState> states() {
        return states;
    }
}
