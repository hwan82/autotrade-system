package com.autotrade.sim.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.OrderQueryResult;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.domain.order.OrderState;
import com.autotrade.domain.order.OrderStatusReport;
import com.autotrade.domain.order.OrderType;
import com.autotrade.domain.order.Side;
import com.autotrade.domain.time.ReplayClock;
import com.autotrade.sim.account.AppliedFill;
import com.autotrade.sim.account.VirtualAccount;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.testing.TestKrxRules;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SIM-06 호가 기반 체결 규칙. 기대값은 손 계산. */
class VirtualExchangeTest {

    private static final Symbol S = Symbol.krx("005930");
    private static final AccountId ACC = new AccountId("T-A01");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 3);
    private static final Instant T0 = Instant.parse("2026-03-03T00:00:00Z"); // KST 09:00

    private ReplayClock clock;
    private VirtualAccount account;
    private VirtualExchange ex;
    private long seq;
    private int keySeq;

    @BeforeEach
    void setUp() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500));
    }

    private void newExchange(Money seed, Duration latency) {
        clock = new ReplayClock(T0, TestKrxRules.INSTANCE.zone());
        account = new VirtualAccount(ACC, seed);
        ex = new VirtualExchange(ACC, TestKrxRules.INSTANCE, CostModels.krDefault(s -> KrxBoard.KOSPI),
                new ExecutionSettings(latency), account, clock);
        seq = 0;
    }

    @Test
    void 시장가는_신호_다음_호가에서_최우선부터_잔량만큼_소진한다() {
        event(book(1000, asks("70100x60", "70200x500"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 100);

        // 신호를 낸 이벤트 직후의 체결로는 체결하지 않는다(지연 0.5초 전)
        assertThat(event(trade(1200, "70100", 50))).isEmpty();
        List<AppliedFill> fills = event(book(2000, asks("70100x60", "70200x500"), bids("70000x400")));

        assertThat(fills).hasSize(2);
        assertFill(fills.get(0), 60, "70100", 1, 0);
        assertFill(fills.get(1), 40, "70200", 2, 4000);
        assertThat(fills.get(0).fill().fillEventSeq()).isGreaterThan(fills.get(0).fill().signalEventSeq());
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
        // 현금 = 1000만 − 4,206,000 − 630 − 2,808,000 − 421
        assertThat(account.cash()).isEqualTo(Money.of(2_984_949, Money.KRW));
    }

    @Test
    void 지연이_지나기_전의_호가로는_체결하지_않는다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofSeconds(2));
        event(book(1000, asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(2000, asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(book(3000, asks("70100x60"), bids("70000x400")))).hasSize(1);
    }

    @Test
    void 지정가는_앞선_잔량이_소멸한_뒤에_체결된다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.LIMIT, "70000", 100);
        event(book(2000, asks("70100x60"), bids("70000x350"))); // 대기 시작, 앞선 잔량 350

        assertThat(event(trade(3000, "70000", 300))).isEmpty(); // 앞선 잔량 50
        List<AppliedFill> f = event(trade(4000, "70000", 120)); // 50 소멸, 70 체결
        assertThat(f).hasSize(1);
        assertFill(f.get(0), 70, "70000", 1, 0);
        assertThat(state(k)).isEqualTo(OrderState.PARTIALLY_FILLED);
        // 지정가보다 유리한 체결이 나오면 앞선 잔량과 무관하게 체결
        f = event(trade(5000, "69900", 500));
        assertFill(f.get(0), 30, "70000", 2, 0);
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 호가에서_같은_가격_잔량이_줄면_앞선_잔량도_줄어든다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.LIMIT, "70000", 10);
        event(book(2000, asks("70100x60"), bids("70000x400"))); // 앞선 400
        event(book(3000, asks("70100x60"), bids("70000x50"))); // 취소로 앞선 50
        assertThat(event(trade(4000, "70000", 55))).singleElement()
                .satisfies(a -> assertThat(a.fill().quantity()).isEqualTo(5));
    }

    @Test
    void 표시_범위_밖_지정가는_그_가격이_표시될_때까지_앞선_잔량을_정하지_않는다() {
        event(book(1000, asks("70100x60"), bids("70000x400", "69900x300")));
        submit(Side.BUY, OrderType.LIMIT, "69800", 10);
        event(book(2000, asks("70100x60"), bids("70000x400", "69900x300"))); // 69800은 표시 범위 밖
        // 앞선 잔량을 모르는 동안 그 가격 체결로는 체결하지 않는다
        assertThat(event(trade(3000, "69800", 50))).isEmpty();
        event(book(4000, asks("70100x60"), bids("69900x300", "69800x200"))); // 앞선 200
        assertThat(event(trade(5000, "69800", 150))).isEmpty(); // 앞선 50
        assertThat(event(trade(6000, "69800", 55))).singleElement()
                .satisfies(a -> assertFill(a, 5, "69800", 1, 0));
        // 지정가보다 유리한 체결은 앞선 잔량과 무관
        assertThat(event(trade(7000, "69700", 100))).singleElement()
                .satisfies(a -> assertFill(a, 5, "69800", 2, 0));
    }

    @Test
    void 대기_중이던_지정가가_나중_호가와_교차하면_지정가로_체결된다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.LIMIT, "70000", 10);
        event(book(2000, asks("70100x60"), bids("70000x400"))); // 대기 시작
        assertThat(event(book(3000, asks("69900x100"), bids("69800x400")))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70000", 1, 0));
    }

    @Test
    void 대기_중인_지정가와_같은_가격의_반대편_호가는_즉시_체결이_아니다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.LIMIT, "70000", 10);
        event(book(2000, asks("70100x60"), bids("70000x400"))); // 대기, 앞선 400
        assertThat(event(book(3000, asks("70000x100"), bids("69900x400")))).isEmpty();
    }

    @Test
    void 지정가_매수가_매도_호가에_닿으면_지정가_이내만_즉시_체결된다() {
        event(book(1000, asks("70100x60", "70200x500"), bids("70000x400")));
        submit(Side.BUY, OrderType.LIMIT, "70100", 100);
        List<AppliedFill> f = event(book(2000, asks("70100x60", "70200x500"), bids("70000x400")));
        assertThat(f).singleElement().satisfies(a -> assertFill(a, 60, "70100", 1, 0));
        // 남은 40은 70100에 대기. 그 가격 호가가 표시 범위 안에 없으므로 앞선 잔량 0
        assertThat(event(trade(3000, "70100", 15))).singleElement()
                .satisfies(a -> assertThat(a.fill().quantity()).isEqualTo(15));
    }

    @Test
    void VI_정지_중에는_체결하지_않는다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.MARKET, null, 10);
        event(status(1500, InstrumentStatus.VOLATILITY_INTERRUPTION));
        assertThat(event(book(2000, asks("70100x60"), bids("70000x400")))).isEmpty();
        event(status(2500, InstrumentStatus.TRADING));
        assertThat(event(book(3000, asks("70100x60"), bids("70000x400")))).hasSize(1);
    }

    @Test
    void 호가가_없는_종목의_시장가는_체결가에_고정_슬리피지를_더해_호가_단위로_올린다() {
        event(trade(1000, "70000", 10));
        submit(Side.BUY, OrderType.MARKET, null, 10);
        // 70,000 × 1.0015 = 70,105 → 호가 단위 100 올림 70,200
        List<AppliedFill> f = event(trade(2000, "70000", 1));
        assertThat(f).singleElement().satisfies(a -> assertFill(a, 10, "70200", 1, 2000));
    }

    @Test
    void 호가가_없는_종목의_지정가는_유리한_체결에서만_체결된다() {
        event(trade(1000, "70000", 10));
        submit(Side.BUY, OrderType.LIMIT, "70000", 10);
        assertThat(event(trade(2000, "70000", 100))).isEmpty();
        assertThat(event(trade(3000, "69900", 100))).hasSize(1);
    }

    @Test
    void 접수_검증_거절() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        assertThat(ack(Side.BUY, OrderType.LIMIT, "70050", 1).message()).isEqualTo("TICK_SIZE");
        assertThat(ack(Side.SELL, OrderType.LIMIT, "70000", 1).message()).isEqualTo("INSUFFICIENT_POSITION");
        assertThat(ack(Side.BUY, OrderType.LIMIT, "70000", 1000).message()).isEqualTo("INSUFFICIENT_CASH");
        // 시세를 본 적 없는 종목의 시장가 매수
        OrderAck noRef = ex.submit(new OrderRequest(nextKey(Symbol.krx("000660"), Side.BUY), ACC, Symbol.krx("000660"),
                Side.BUY, OrderIntent.ENTRY, OrderType.MARKET, null, 1));
        assertThat(noRef.message()).isEqualTo("NO_REFERENCE_PRICE");
        OrderAck wrongMarket = ex.submit(new OrderRequest(nextKey(Symbol.us("AAPL"), Side.BUY), ACC, Symbol.us("AAPL"),
                Side.BUY, OrderIntent.ENTRY, OrderType.LIMIT, BigDecimal.TEN, 1));
        assertThat(wrongMarket.state()).isEqualTo(OrderState.REJECTED);
        assertThat(wrongMarket.message()).isEqualTo("WRONG_MARKET");
    }

    @Test
    void 미체결_매수가_예약한_현금은_다음_주문에서_빠진다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        assertThat(ack(Side.BUY, OrderType.LIMIT, "70000", 100).state()).isEqualTo(OrderState.ACCEPTED);
        // 예약 7,001,050원 → 남은 약 300만 원으로 50주(350만 원)는 못 산다
        assertThat(ack(Side.BUY, OrderType.LIMIT, "70000", 50).message()).isEqualTo("INSUFFICIENT_CASH");
    }

    @Test
    void 같은_멱등_키는_새_주문을_만들지_않는다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = nextKey(S, Side.BUY);
        OrderRequest r = new OrderRequest(k, ACC, S, Side.BUY, OrderIntent.ENTRY, OrderType.LIMIT,
                new BigDecimal("70000"), 10);
        assertThat(ex.submit(r).duplicateKey()).isFalse();
        OrderAck again = ex.submit(r);
        assertThat(again.duplicateKey()).isTrue();
        assertThat(again.state()).isEqualTo(OrderState.ACCEPTED);
    }

    @Test
    void 다른_계좌의_주문은_받지_않는다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        OrderRequest other = new OrderRequest(nextKey(S, Side.BUY), new AccountId("OTHER"), S, Side.BUY,
                OrderIntent.ENTRY, OrderType.MARKET, null, 1);
        assertThatThrownBy(() -> ex.submit(other)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 장_마감에_남은_주문을_취소하고_조회는_원장_기준이다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.LIMIT, "69000", 10);
        ex.endOfDay();
        assertThat(state(k)).isEqualTo(OrderState.CANCELLED);
        assertThat(ex.reasonOf(k)).isEqualTo(OrderRejectedReason.DAY_END);
        assertThat(ex.hasOpenOrder(S)).isFalse();
        assertThat(ex.query(nextKey(S, Side.SELL))).isEqualTo(OrderQueryResult.NotFound.INSTANCE);
    }

    @Test
    void 체결_시점_현금이_모자라면_살_수_있는_만큼만_체결하고_잔량을_취소한다() {
        newExchange(Money.of(1_000_000, Money.KRW), Duration.ZERO);
        event(book(1000, asks("9000x100"), bids("8990x100")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 100); // 예약 기준 약 90만 원
        // 체결 시 매도 호가가 급등: 11,000원 × 100 = 110만 원 → 90주(990,000 + 수수료 148)만
        List<AppliedFill> f = event(book(2000, asks("11000x100"), bids("10990x100")));
        assertThat(f).singleElement().satisfies(a -> assertThat(a.fill().quantity()).isEqualTo(90));
        assertThat(state(k)).isEqualTo(OrderState.CANCELLED);
        assertThat(ex.reasonOf(k)).isEqualTo(OrderRejectedReason.CASH_SHORT_AT_FILL);
        assertThat(account.cash()).isEqualTo(Money.of(1_000_000 - 990_000 - 148, Money.KRW));
    }

    @Test
    void 이벤트_순서가_거꾸로면_거부한다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        OrderBookSnapshot old = new OrderBookSnapshot(S, 0, T0, T0, List.of(), List.of());
        assertThatThrownBy(() -> ex.onEvent(old)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 도우미 ----

    private List<AppliedFill> event(MarketEvent e) {
        clock.advanceToAtLeast(e.receivedAt());
        return ex.onEvent(e);
    }

    private IdempotencyKey submit(Side side, OrderType type, String price, long qty) {
        IdempotencyKey k = nextKey(S, side);
        OrderAck a = ex.submit(request(k, side, type, price, qty));
        assertThat(a.state()).as(a.message()).isEqualTo(OrderState.ACCEPTED);
        return k;
    }

    private OrderAck ack(Side side, OrderType type, String price, long qty) {
        return ex.submit(request(nextKey(S, side), side, type, price, qty));
    }

    private OrderRequest request(IdempotencyKey k, Side side, OrderType type, String price, long qty) {
        return new OrderRequest(k, ACC, S, side, side == Side.BUY ? OrderIntent.ENTRY : OrderIntent.EXIT, type,
                price == null ? null : new BigDecimal(price), qty);
    }

    private IdempotencyKey nextKey(Symbol s, Side side) {
        return IdempotencyKey.of(DAY, s, side, ++keySeq);
    }

    private OrderState state(IdempotencyKey k) {
        OrderStatusReport r = ((OrderQueryResult.Found) ex.query(k)).report();
        return r.state();
    }

    private static void assertFill(AppliedFill a, long qty, String price, int fillSeq, long slippage) {
        assertThat(a.fill().quantity()).isEqualTo(qty);
        assertThat(a.fill().price()).isEqualByComparingTo(price);
        assertThat(a.fill().fillSeq()).isEqualTo(fillSeq);
        assertThat(a.fill().slippage()).isEqualTo(Money.of(slippage, Money.KRW));
    }

    private OrderBookSnapshot book(long ms, List<PriceLevel> asks, List<PriceLevel> bids) {
        Instant t = T0.plusMillis(ms);
        return new OrderBookSnapshot(S, ++seq, t, t.plusMillis(50), asks, bids);
    }

    private TradeTick trade(long ms, String price, long qty) {
        Instant t = T0.plusMillis(ms);
        return new TradeTick(S, ++seq, t, t.plusMillis(50), new BigDecimal(price), qty);
    }

    private InstrumentStatusChanged status(long ms, InstrumentStatus st) {
        Instant t = T0.plusMillis(ms);
        return new InstrumentStatusChanged(S, ++seq, t, t.plusMillis(50), st);
    }

    private static List<PriceLevel> asks(String... levels) {
        return levels(levels);
    }

    private static List<PriceLevel> bids(String... levels) {
        return levels(levels);
    }

    private static List<PriceLevel> levels(String... levels) {
        List<PriceLevel> out = new ArrayList<>();
        for (String l : levels) {
            String[] pq = l.split("x");
            out.add(new PriceLevel(new BigDecimal(pq[0]), Long.parseLong(pq[1])));
        }
        return out;
    }
}
