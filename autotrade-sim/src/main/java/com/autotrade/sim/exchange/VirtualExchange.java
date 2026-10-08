package com.autotrade.sim.exchange;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderAck;
import com.autotrade.domain.order.OrderGateway;
import com.autotrade.domain.order.OrderQueryResult;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.order.OrderStatusReport;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.VirtualAccount;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.FeeBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 가상 거래소: 계좌 하나의 {@link OrderGateway} 가상 구현(SIM-01·06). 증권사 주문 API를 호출하지 않는다.
 *
 * <p>체결 규칙(ADR-004 2.2, {@link ExecutionSettings#RULES_VERSION})
 * <ul>
 *   <li><b>유효 시점</b>: 주문은 신호 이벤트(T)보다 sequence가 크고, 수신 시각이 신호 시각 + 주문 지연 이상인 이벤트부터
 *       체결에 쓴다. 신호를 만든 이벤트로는 체결하지 않는다(미래 정보 금지).</li>
 *   <li><b>시장가</b>: 유효해진 뒤 첫 호가 스냅샷의 반대편 최우선 호가부터 잔량만큼 소진한다. 남은 수량은 다음 스냅샷에서
 *       이어서 소진한다. 같은 스냅샷에서 이 계좌의 앞선 주문이 먹은 잔량은 빼고 본다.</li>
 *   <li><b>지정가</b>: 유효해질 때 지정가 이내 반대편 호가가 있으면 그만큼 즉시 체결(시장가와 같은 소진). 남은 수량은
 *       지정가에 대기하며, 대기 시작 시 같은 가격의 기존 잔량을 "앞선 잔량"으로 둔다. 이후 그 가격 체결(TradeTick)이
 *       앞선 잔량부터 줄이고, 넘친 수량만큼 체결된다. 지정가보다 유리한 가격의 체결이 나오면 앞선 잔량과 무관하게 체결된다.
 *       호가 스냅샷에서 그 가격 잔량이 앞선 잔량보다 작아지면(취소) 앞선 잔량도 줄인다. 대기 체결가는 지정가다.</li>
 *   <li><b>호가가 없는 종목</b>(체결 데이터만 있음): 시장가는 다음 체결가에 고정 슬리피지({@link CostModel#slippageRate()})를
 *       불리한 방향으로 더해 호가 단위로 올림·내림한 가격으로 전량 체결, 지정가는 지정가보다 유리한 체결이 나올 때만
 *       지정가로 체결(앞선 잔량을 모르므로 보수적으로).</li>
 *   <li><b>거래 상태</b>: 종목 상태가 TRADING이 아니면(VI, LULD, 정지) 체결하지 않는다(OVS-06). 주문은 유지한다.</li>
 *   <li><b>현금</b>: 매수 체결 직전 (현금 − 다른 주문의 예약 현금)으로 살 수 있는 수량까지만 체결하고, 모자란 잔량은 취소한다.</li>
 *   <li><b>당일 유효</b>: {@link #endOfDay}에서 남은 주문을 취소한다.</li>
 *   <li>우리 주문이 시장에 준 영향(소진한 잔량)은 다음 스냅샷에 이어지지 않는다 `[가정]`: 실제 스냅샷이 시장 상태다.
 *       주문 규모가 호가 잔량보다 충분히 작다는 전제이며, 큰 주문의 성과는 과대평가될 수 있다.</li>
 * </ul>
 *
 * <p>Run 하나 전용, 단일 스레드. {@link #onEvent}를 이벤트 sequence 순서대로 불러야 한다.
 */
public final class VirtualExchange implements OrderGateway {

    private final AccountId account;
    private final MarketRules rules;
    private final CostModel cost;
    private final ExecutionSettings settings;
    private final VirtualAccount ledger;
    private final Clock clock;

    private final Map<IdempotencyKey, VirtualOrder> orders = new HashMap<>();
    /** 미체결 주문, 접수 순서. */
    private final LinkedHashMap<IdempotencyKey, VirtualOrder> open = new LinkedHashMap<>();
    private final Map<Symbol, OrderBookSnapshot> books = new HashMap<>();
    private final Map<Symbol, BigDecimal> lastTrade = new HashMap<>();
    private final Map<Symbol, InstrumentStatus> status = new HashMap<>();
    private final Set<Symbol> booksSeen = new HashSet<>();
    private final List<AppliedFill> newFills = new ArrayList<>();
    private long currentSeq = Long.MIN_VALUE;
    private Instant currentReceivedAt;
    private long orderCounter;

    public VirtualExchange(
            AccountId account, MarketRules rules, CostModel cost, ExecutionSettings settings, VirtualAccount ledger,
            Clock clock) {
        this.account = Objects.requireNonNull(account, "account");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!ledger.id().equals(account)) {
            throw new IllegalArgumentException("거래소 계좌와 원장 계좌가 다릅니다");
        }
        if (cost.market() != rules.market()) {
            throw new IllegalArgumentException("비용 모델 시장과 시장 규칙이 다릅니다");
        }
    }

    @Override
    public AccountId account() {
        return account;
    }

    /**
     * 시세 이벤트 하나를 반영한다: 유효해진 주문을 이 이벤트로 체결한 뒤 시세 상태를 갱신한다.
     *
     * @return 이 이벤트로 생긴 체결(계좌에 이미 반영됨)
     */
    public List<AppliedFill> onEvent(MarketEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.sequence() <= currentSeq) {
            throw new IllegalArgumentException("이벤트 순서가 거꾸로입니다: " + currentSeq + " -> " + event.sequence());
        }
        currentSeq = event.sequence();
        currentReceivedAt = event.receivedAt();
        newFills.clear();
        if (event instanceof InstrumentStatusChanged s) {
            status.put(s.symbol(), s.status());
        } else if (event instanceof OrderBookSnapshot book) {
            booksSeen.add(book.symbol());
            matchBook(book);
            books.put(book.symbol(), book);
        } else if (event instanceof TradeTick tick) {
            matchTrade(tick);
            lastTrade.put(tick.symbol(), tick.price());
        }
        // 그 밖의 이벤트(예: 2단계 DataGap)는 체결에 쓰지 않는다
        return List.copyOf(newFills);
    }

    @Override
    public OrderAck submit(OrderRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.account().equals(account)) {
            throw new IllegalArgumentException("이 거래소의 계좌가 아닙니다: " + request.account());
        }
        VirtualOrder existing = orders.get(request.key());
        if (existing != null) {
            return new OrderAck(request.key(), existing.state, true, existing.orderId, "이미 접수된 멱등 키");
        }
        if (currentReceivedAt == null) {
            throw new IllegalStateException("첫 시세 이벤트 전에는 주문할 수 없습니다");
        }
        VirtualOrder order = new VirtualOrder(request, "V-" + account + "-" + (++orderCounter), currentSeq,
                currentReceivedAt, clock.instant().plus(settings.orderLatency()));
        orders.put(request.key(), order);
        OrderRejectedReason reason = validate(order);
        if (reason != null) {
            order.state = OrderState.REJECTED;
            order.reason = reason;
            return new OrderAck(request.key(), OrderState.REJECTED, false, order.orderId, reason.name());
        }
        order.state = OrderState.ACCEPTED;
        open.put(request.key(), order);
        return new OrderAck(request.key(), OrderState.ACCEPTED, false, order.orderId, "");
    }

    @Override
    public OrderQueryResult query(IdempotencyKey key) {
        VirtualOrder o = orders.get(Objects.requireNonNull(key, "key"));
        if (o == null) {
            return OrderQueryResult.NotFound.INSTANCE;
        }
        return new OrderQueryResult.Found(new OrderStatusReport(
                key, o.state, o.request.quantity(), o.filled, o.averagePrice()));
    }

    @Override
    public OrderAck cancel(IdempotencyKey key) {
        VirtualOrder o = orders.get(Objects.requireNonNull(key, "key"));
        if (o == null) {
            return new OrderAck(key, OrderState.REJECTED, false, null, "주문 없음");
        }
        if (!o.state.isTerminal()) {
            close(o, OrderState.CANCELLED, null);
        }
        return new OrderAck(key, o.state, false, o.orderId, "");
    }

    /** 거래일 종료: 남은 주문을 모두 취소한다(당일 유효). */
    public void endOfDay() {
        for (VirtualOrder o : List.copyOf(open.values())) {
            close(o, OrderState.CANCELLED, OrderRejectedReason.DAY_END);
        }
    }

    public boolean hasOpenOrder(Symbol symbol) {
        for (VirtualOrder o : open.values()) {
            if (o.request.symbol().equals(symbol)) {
                return true;
            }
        }
        return false;
    }

    /** 주문의 거절·취소 사유. 없으면 null. */
    public OrderRejectedReason reasonOf(IdempotencyKey key) {
        VirtualOrder o = orders.get(key);
        return o == null ? null : o.reason;
    }

    /** 평가 가격: 마지막 체결가, 없으면 최우선 매수 호가, 둘 다 없으면 null. */
    public BigDecimal markPrice(Symbol symbol) {
        BigDecimal last = lastTrade.get(symbol);
        if (last != null) {
            return last;
        }
        OrderBookSnapshot book = books.get(symbol);
        return book == null || book.bids().isEmpty() ? null : book.bids().get(0).price();
    }

    /** 마지막 체결가. 없으면 null. */
    public BigDecimal lastTradePrice(Symbol symbol) {
        return lastTrade.get(symbol);
    }

    // ---- 접수 검증 ----

    private OrderRejectedReason validate(VirtualOrder o) {
        OrderRequest r = o.request;
        if (r.symbol().market() != rules.market()) {
            return OrderRejectedReason.WRONG_MARKET;
        }
        if (r.quantity() % rules.quantityStep() != 0) {
            return OrderRejectedReason.QUANTITY_STEP;
        }
        if (r.type() == OrderType.LIMIT
                && rules.roundToTick(r.symbol(), r.limitPrice(), RoundingMode.DOWN).compareTo(r.limitPrice()) != 0) {
            return OrderRejectedReason.TICK_SIZE;
        }
        if (r.side() == Side.SELL) {
            long pendingSell = 0;
            for (VirtualOrder other : open.values()) {
                if (other.request.side() == Side.SELL && other.request.symbol().equals(r.symbol())) {
                    pendingSell += other.remaining();
                }
            }
            return r.quantity() > ledger.quantityOf(r.symbol()) - pendingSell
                    ? OrderRejectedReason.INSUFFICIENT_POSITION : null;
        }
        BigDecimal ref = referenceBuyPrice(o);
        if (ref == null) {
            return OrderRejectedReason.NO_REFERENCE_PRICE;
        }
        o.referencePrice = ref;
        o.reserved = estimate(o, r.quantity());
        return o.reserved.compareTo(availableCash(null)) > 0 ? OrderRejectedReason.INSUFFICIENT_CASH : null;
    }

    /** 매수 예약 기준 가격: 지정가, 시장가면 최우선 매도 호가(없으면 마지막 체결가) × (1 + 슬리피지율). */
    private BigDecimal referenceBuyPrice(VirtualOrder o) {
        if (o.request.type() == OrderType.LIMIT) {
            return o.request.limitPrice();
        }
        Symbol s = o.request.symbol();
        OrderBookSnapshot book = books.get(s);
        BigDecimal base = book != null && !book.asks().isEmpty() ? book.asks().get(0).price() : lastTrade.get(s);
        return base == null ? null : base.multiply(BigDecimal.ONE.add(cost.slippageRate()));
    }

    private Money estimate(VirtualOrder o, long quantity) {
        if (quantity == 0) {
            return Money.zero(ledger.currency());
        }
        LocalDate date = rules.tradingDate(currentReceivedAt);
        Money notional = new Money(o.referencePrice.multiply(BigDecimal.valueOf(quantity)), ledger.currency());
        return notional.plus(cost.buyFees(o.request.symbol(), date, o.referencePrice, quantity))
                .roundedToCurrency(RoundingMode.CEILING);
    }

    /** 현금 − 다른 미체결 매수 주문의 예약 현금. */
    private Money availableCash(VirtualOrder except) {
        Money reserved = Money.zero(ledger.currency());
        for (VirtualOrder o : open.values()) {
            if (o != except && o.request.side() == Side.BUY) {
                reserved = reserved.plus(o.reserved);
            }
        }
        return ledger.cash().minus(reserved);
    }

    // ---- 체결 ----

    private boolean live(VirtualOrder o, MarketEvent e) {
        return e.sequence() > o.signalSeq && !e.receivedAt().isBefore(o.activeFrom);
    }

    private boolean tradable(Symbol s) {
        InstrumentStatus st = status.get(s);
        return st == null || st == InstrumentStatus.TRADING;
    }

    private void matchBook(OrderBookSnapshot book) {
        if (!tradable(book.symbol())) {
            return;
        }
        // 이 스냅샷에서 이 계좌 주문들이 이미 먹은 잔량(가격 → 수량)
        Map<BigDecimal, Long> takenAsk = new HashMap<>();
        Map<BigDecimal, Long> takenBid = new HashMap<>();
        for (VirtualOrder o : List.copyOf(open.values())) {
            if (!o.request.symbol().equals(book.symbol()) || !live(o, book)) {
                continue;
            }
            boolean buy = o.request.side() == Side.BUY;
            List<PriceLevel> opposite = buy ? book.asks() : book.bids();
            Map<BigDecimal, Long> taken = buy ? takenAsk : takenBid;
            BigDecimal limit = o.request.type() == OrderType.LIMIT ? o.request.limitPrice() : null;
            BigDecimal best = opposite.isEmpty() ? null : opposite.get(0).price();
            for (PriceLevel level : opposite) {
                if (o.remaining() == 0 || !o.isOpen()) {
                    break;
                }
                if (limit != null && (buy ? level.price().compareTo(limit) > 0 : level.price().compareTo(limit) < 0)) {
                    break;
                }
                // 대기 중인 지정가와 같은 가격의 반대편 잔량은 교차가 아니다. 앞선 잔량 규칙(체결 틱)으로만 체결한다
                if (o.resting && level.price().compareTo(limit) == 0) {
                    break;
                }
                long available = level.quantity() - taken.getOrDefault(level.price(), 0L);
                if (available <= 0) {
                    continue;
                }
                long qty = Math.min(available, o.remaining());
                // 이미 대기 중이던 지정가는 먼저 와 있던 주문이므로 지정가로 체결한다(체결 틱 경로와 같다)
                BigDecimal price = o.resting ? limit : level.price();
                BigDecimal impact = o.resting ? BigDecimal.ZERO
                        : level.price().subtract(best).abs().multiply(BigDecimal.valueOf(qty));
                long done = fill(o, book, price, qty, impact);
                taken.merge(level.price(), done, Long::sum);
            }
            if (limit != null && o.isOpen() && o.remaining() > 0) {
                updateQueue(o, buy ? book.bids() : book.asks(), buy);
                o.resting = true;
            }
        }
    }

    /**
     * 지정가 대기 주문의 앞선 잔량을 스냅샷으로 맞춘다. 처음이면 같은 가격 잔량으로 시작한다.
     * 지정가가 표시된 호가 범위 밖이면 앞선 잔량을 알 수 없으므로 정하지 않고(null) 둔다.
     * 정해지기 전에는 지정가보다 유리한 체결로만 체결된다.
     */
    private void updateQueue(VirtualOrder o, List<PriceLevel> sameSide, boolean buy) {
        BigDecimal limit = o.request.limitPrice();
        Long levelQty = null;
        for (PriceLevel l : sameSide) {
            if (l.price().compareTo(limit) == 0) {
                levelQty = l.quantity();
                break;
            }
        }
        if (levelQty == null) {
            // 표시된 호가 범위 안인데 그 가격이 없으면 앞선 잔량 0, 범위 밖이면 알 수 없으므로 그대로 둔다
            boolean withinRange = !sameSide.isEmpty() && (buy
                    ? limit.compareTo(sameSide.get(sameSide.size() - 1).price()) >= 0
                    : limit.compareTo(sameSide.get(sameSide.size() - 1).price()) <= 0);
            if (!withinRange) {
                return;
            }
            levelQty = 0L;
        }
        o.queueAhead = o.queueAhead == null ? levelQty : Math.min(o.queueAhead, levelQty);
    }

    private void matchTrade(TradeTick tick) {
        Symbol s = tick.symbol();
        if (!tradable(s)) {
            return;
        }
        boolean hasBook = booksSeen.contains(s);
        long tickLeft = tick.quantity();
        for (VirtualOrder o : List.copyOf(open.values())) {
            if (!o.request.symbol().equals(s) || !live(o, tick) || tickLeft <= 0) {
                continue;
            }
            boolean buy = o.request.side() == Side.BUY;
            if (o.request.type() == OrderType.MARKET) {
                if (hasBook) {
                    continue; // 호가가 있는 종목의 시장가는 스냅샷으로만 체결
                }
                BigDecimal raw = tick.price().multiply(buy
                        ? BigDecimal.ONE.add(cost.slippageRate()) : BigDecimal.ONE.subtract(cost.slippageRate()));
                BigDecimal price = rules.roundToTick(s, raw, buy ? RoundingMode.CEILING : RoundingMode.FLOOR);
                BigDecimal slip = price.subtract(tick.price()).abs();
                long qty = o.remaining();
                fill(o, tick, price, qty, slip.multiply(BigDecimal.valueOf(qty)));
                continue;
            }
            BigDecimal limit = o.request.limitPrice();
            int cmp = tick.price().compareTo(limit);
            boolean through = buy ? cmp < 0 : cmp > 0;
            boolean atLimit = cmp == 0;
            long qty;
            if (through) {
                qty = Math.min(o.remaining(), tickLeft);
            } else if (atLimit && hasBook && o.queueAhead != null) {
                // 한계: 같은 가격에 이 계좌 주문이 둘 이상이면 뒤 주문의 앞선 잔량에 앞 주문 수량을 넣지 않는다(소액 전제)
                long ahead = o.queueAhead;
                long excess = tickLeft - ahead;
                o.queueAhead = Math.max(0L, ahead - tickLeft);
                qty = Math.min(o.remaining(), Math.max(0L, excess));
            } else {
                continue;
            }
            if (qty > 0) {
                tickLeft -= fill(o, tick, limit, qty, BigDecimal.ZERO);
            }
        }
    }

    /**
     * 체결 하나를 만들고 계좌에 반영한다. 매수는 현금으로 살 수 있는 만큼만 체결하고 모자란 잔량은 취소한다.
     *
     * @return 실제 체결 수량
     */
    private long fill(VirtualOrder o, MarketEvent e, BigDecimal price, long wanted, BigDecimal slippageAmount) {
        long qty = wanted;
        LocalDate date = rules.tradingDate(e.exchangeTime());
        Symbol s = o.request.symbol();
        if (o.request.side() == Side.BUY) {
            Money budget = availableCash(o);
            long step = rules.quantityStep();
            long max = budget.amount().divide(price, 0, RoundingMode.FLOOR).longValueExact();
            qty = Math.min(qty, max - max % step);
            while (qty > 0 && buyCost(s, date, price, qty).compareTo(budget) > 0) {
                qty -= step;
            }
            if (qty < wanted) {
                if (qty > 0) {
                    record(o, e, price, qty, slippageAmount.multiply(BigDecimal.valueOf(qty))
                            .divide(BigDecimal.valueOf(wanted), 10, RoundingMode.HALF_EVEN), date);
                }
                close(o, OrderState.CANCELLED, OrderRejectedReason.CASH_SHORT_AT_FILL);
                return qty;
            }
        }
        record(o, e, price, qty, slippageAmount, date);
        return qty;
    }

    private Money buyCost(Symbol s, LocalDate date, BigDecimal price, long qty) {
        return new Money(price.multiply(BigDecimal.valueOf(qty)), ledger.currency())
                .plus(cost.fees(Side.BUY, s, date, price, qty).total());
    }

    private void record(VirtualOrder o, MarketEvent e, BigDecimal price, long qty, BigDecimal slippage, LocalDate date) {
        FeeBreakdown fees = cost.fees(o.request.side(), o.request.symbol(), date, price, qty);
        Fill f = new Fill(o.request.key(), ++o.fillCount, o.request.symbol(), o.request.side(), o.request.intent(),
                qty, price, fees,
                new Money(slippage, ledger.currency()).rounded(4, RoundingMode.HALF_EVEN),
                o.signalSeq, e.sequence(), o.signalTime, e.exchangeTime(), date);
        newFills.add(ledger.apply(f));
        o.filled += qty;
        o.filledNotional = o.filledNotional.add(price.multiply(BigDecimal.valueOf(qty)));
        if (o.remaining() == 0) {
            close(o, OrderState.FILLED, null);
        } else {
            o.state = OrderState.PARTIALLY_FILLED;
            if (o.request.side() == Side.BUY) {
                o.reserved = estimate(o, o.remaining());
            }
        }
    }

    private void close(VirtualOrder o, OrderState state, OrderRejectedReason reason) {
        o.state = state;
        o.reason = reason;
        o.reserved = Money.zero(ledger.currency());
        open.remove(o.request.key());
    }

    /** 주문 하나의 가변 상태. 이 거래소 밖으로 나가지 않는다. */
    private final class VirtualOrder {
        private final OrderRequest request;
        private final String orderId;
        private final long signalSeq;
        private final Instant signalTime;
        private final Instant activeFrom;
        private OrderState state = OrderState.SUBMITTED;
        private OrderRejectedReason reason;
        private long filled;
        private BigDecimal filledNotional = BigDecimal.ZERO;
        private int fillCount;
        private Long queueAhead;
        /** 한 번이라도 호가 스냅샷을 지나 대기한 지정가 주문인가. */
        private boolean resting;
        private BigDecimal referencePrice;
        private Money reserved = Money.zero(ledger.currency());

        VirtualOrder(OrderRequest request, String orderId, long signalSeq, Instant signalTime, Instant activeFrom) {
            this.request = request;
            this.orderId = orderId;
            this.signalSeq = signalSeq;
            this.signalTime = signalTime;
            this.activeFrom = activeFrom;
        }

        long remaining() {
            return request.quantity() - filled;
        }

        boolean isOpen() {
            return !state.isTerminal();
        }

        BigDecimal averagePrice() {
            return filled == 0 ? null : filledNotional.divide(BigDecimal.valueOf(filled), 6, RoundingMode.HALF_EVEN)
                    .stripTrailingZeros();
        }
    }
}
