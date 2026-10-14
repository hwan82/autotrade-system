package com.autotrade.sim.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.Money;
import com.autotrade.domain.SessionPhase;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.DataGap;
import com.autotrade.domain.market.InstrumentStatus;
import com.autotrade.domain.market.InstrumentStatusChanged;
import com.autotrade.domain.market.Market;
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
import java.time.LocalTime;
import java.time.ZoneId;
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
        newExchange(seed, latency, TestKrxRules.INSTANCE);
    }

    private void newExchange(Money seed, Duration latency, MarketRules rules) {
        newExchange(seed, latency, rules, true);
    }

    private void newExchange(Money seed, Duration latency, MarketRules rules, boolean phases) {
        clock = new ReplayClock(T0.minus(Duration.ofHours(1)), TestKrxRules.INSTANCE.zone());
        account = new VirtualAccount(ACC, seed);
        ex = new VirtualExchange(ACC, rules, CostModels.krDefault(s -> KrxBoard.KOSPI),
                new ExecutionSettings(latency), account, clock, phases);
        seq = 0;
    }

    /**
     * KST 08:30~09:00 시가 단일가, 09:00~15:20 접속 매매, 15:20~15:30 종가 단일가, 그 밖은 장 밖(휴장일 없음). 시가 단일가로
     * 연다(종목별 첫 체결 전까지 접속 매매 아님).
     */
    private static final MarketRules SESSIONS = new MarketRules() {
        @Override
        public Market market() {
            return Market.KRX;
        }

        @Override
        public ZoneId zone() {
            return TestKrxRules.INSTANCE.zone();
        }

        @Override
        public boolean isRegularSession(Instant instant) {
            SessionPhase p = sessionPhase(instant);
            return p == SessionPhase.CONTINUOUS || p == SessionPhase.CLOSING_AUCTION;
        }

        @Override
        public SessionPhase sessionPhase(Instant instant) {
            LocalTime t = instant.atZone(zone()).toLocalTime();
            if (t.isBefore(LocalTime.of(8, 30)) || !t.isBefore(LocalTime.of(15, 30))) {
                return SessionPhase.CLOSED;
            }
            if (t.isBefore(LocalTime.of(9, 0))) {
                return SessionPhase.OPENING_AUCTION;
            }
            return t.isBefore(LocalTime.of(15, 20)) ? SessionPhase.CONTINUOUS : SessionPhase.CLOSING_AUCTION;
        }

        @Override
        public boolean opensWithAuction() {
            return true;
        }

        @Override
        public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
            return TestKrxRules.INSTANCE.tickSize(symbol, price);
        }
    };

    /**
     * 미국 기본 규칙처럼 단일가 구간이 없다: KST 09:00~15:30 접속 매매, 그 밖은 장 밖({@link MarketRules#sessionPhase} 기본 구현).
     * 폐장 직후 종가 크로스 체결이 장 밖 시각으로 온다.
     */
    private static final MarketRules NO_AUCTION = new MarketRules() {
        @Override
        public Market market() {
            return Market.KRX;
        }

        @Override
        public ZoneId zone() {
            return TestKrxRules.INSTANCE.zone();
        }

        @Override
        public boolean isRegularSession(Instant instant) {
            LocalTime t = instant.atZone(zone()).toLocalTime();
            return !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(15, 30));
        }

        @Override
        public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
            return TestKrxRules.INSTANCE.tickSize(symbol, price);
        }
    };

    /** KST 시각(T0 = 09:00 기준 밀리초). */
    private static long kst(int hour, int minute, int second) {
        return Duration.ofHours(hour - 9).plusMinutes(minute).plusSeconds(second).toMillis();
    }

    @Test
    void 장전_예상_호가로는_체결하지_않고_시가_단일가_체결가로_체결한다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // QA 탐침 S2: 08:50 호가 → 시장가 매수 10주 → 08:50:02 호가. BOOK-V2는 여기서 체결했다
        event(book(kst(8, 50, 0), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(8, 50, 2), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(book(kst(8, 59, 59), asks("70100x60"), bids("70000x400")))).isEmpty();
        // 개장 뒤 첫 체결이 시가 단일가 체결: 그 가격 하나로, 슬리피지 0
        List<AppliedFill> f = event(trade(kst(9, 0, 3), "70200", 5_000));
        assertThat(f).singleElement().satisfies(a -> assertFill(a, 10, "70200", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
        // 현금 = 1000만 − 702,000 − 수수료 105(702,000 × 0.015% 절사)
        assertThat(account.cash()).isEqualTo(Money.of(9_297_895, Money.KRW));
    }

    @Test
    void 개장_뒤_첫_체결_전_호가로는_체결하지_않고_첫_체결을_시가_단일가_체결로_본다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // architect 탐침 P1: 08:50 시장가 매수 → 09:00:01 호가(접수 마감이 늦춰진 랜덤 엔드 구간). BOOK-V3 첫 구현은 여기서 체결했다
        event(book(kst(8, 50, 0), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(8, 50, 2), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(book(kst(9, 0, 1), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(trade(kst(9, 0, 20), "70200", 5_000))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70200", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
        assertThat(account.cash()).isEqualTo(Money.of(9_297_895, Money.KRW));
    }

    @Test
    void 개장_뒤_첫_체결_전에_낸_주문도_시가_단일가에_참여하고_그_뒤_주문은_접속_매매로_체결된다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // architect 탐침 P2: 09:00:01 호가 뒤 시장가 매수 → 09:00:02 호가. 아직 시가 단일가 접수 중이다
        event(book(kst(9, 0, 1), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(9, 0, 2), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(trade(kst(9, 0, 5), "70200", 5_000))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70200", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
        // 첫 체결 뒤로는 접속 매매: 다음 주문은 그 뒤 호가에서 최우선 매도 호가로 체결
        IdempotencyKey k2 = submit(Side.BUY, OrderType.MARKET, null, 5);
        assertThat(event(book(kst(9, 0, 6), asks("70300x60"), bids("70200x400")))).singleElement()
                .satisfies(a -> assertFill(a, 5, "70300", 1, 0));
        assertThat(state(k2)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 사이_이벤트_없이_시가_단일가_체결_직전에_유효해진_주문도_참여한다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(8, 59, 58), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(trade(kst(9, 0, 4), "70200", 5_000))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70200", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 시가_단일가_체결_전_시세_공백_뒤에는_새_호가부터_접속_매매로_본다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(9, 0, 1), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        event(gap(kst(9, 0, 30)));
        // 공백 사이에 시가 단일가 체결이 지나갔을 수 있다: 참여 표시는 지우고, 새 호가로 체결(D-068 공백 뒤 첫 스냅샷)
        assertThat(event(book(kst(9, 0, 31), asks("70300x60"), bids("70200x400")))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70300", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 매매_단계를_보지_않는_거래소는_장전_호가로도_체결한다() {
        // 이벤트 시각이 합성인 데이터(일봉 경로)용. BOOK-V2와 같다
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS, false);
        event(book(kst(8, 50, 0), asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(8, 50, 2), asks("70100x60"), bids("70000x400")))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70100", 1, 0));
    }

    @Test
    void 종가_단일가_구간_주문은_폐장_뒤_첫_체결가로_그_수량_안에서만_체결된다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // QA 탐침 S1: 15:20:10 호가 → 시장가 매수 100주 → 15:20:12 호가. BOOK-V2는 여기서 2건 체결했다
        event(book(kst(15, 20, 10), asks("70100x60", "70200x500"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 100);
        assertThat(event(book(kst(15, 20, 12), asks("70100x60", "70200x500"), bids("70000x400")))).isEmpty();
        // 종가 단일가 체결 30주: 그 수량까지만, 남은 70주는 접속 매매가 없어 체결되지 않는다
        assertThat(event(trade(kst(15, 30, 0), "70300", 30))).singleElement()
                .satisfies(a -> assertFill(a, 30, "70300", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.PARTIALLY_FILLED);
        // 폐장 뒤 시간외 체결·호가로는 체결하지 않는다
        assertThat(event(book(kst(15, 40, 0), asks("70300x900"), bids("70200x400")))).isEmpty();
        assertThat(event(trade(kst(15, 40, 1), "70300", 1_000))).isEmpty();
        ex.endOfDay();
        assertThat(state(k)).isEqualTo(OrderState.EXPIRED);
    }

    @Test
    void 단일가_체결가가_지정가_밖이면_체결하지_않고_접속_매매_주문으로_이어진다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(8, 55, 0), asks("70100x60"), bids("69900x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.LIMIT, "70000", 10);
        event(book(kst(8, 55, 1), asks("70100x60"), bids("69900x400")));
        assertThat(event(trade(kst(9, 0, 1), "70100", 5_000))).isEmpty();
        assertThat(state(k)).isEqualTo(OrderState.ACCEPTED);
        // 접속 매매 호가와 교차하면 그 호가로 체결
        assertThat(event(book(kst(9, 0, 2), asks("70000x5"), bids("69900x400")))).singleElement()
                .satisfies(a -> assertFill(a, 5, "70000", 1, 0));
    }

    @Test
    void 단일가_구간의_시세_공백은_단일가_참여를_지운다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(15, 21, 0), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        event(book(kst(15, 21, 1), asks("70100x60"), bids("70000x400")));
        event(gap(kst(15, 25, 0)));
        // 공백 사이에 단일가 체결이 지나갔을 수 있어 폐장 뒤 첫 체결을 단일가로 보지 않는다
        assertThat(event(trade(kst(15, 40, 0), "70300", 1_000))).isEmpty();
        ex.endOfDay();
        assertThat(state(k)).isEqualTo(OrderState.EXPIRED);
    }

    @Test
    void 종가_단일가_체결_틱이_없으면_장_밖_체결로_종가_참여_주문을_체결하지_않는다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // QA 3단계 R3 탐침 P3: 15:20:10 시장가 매수 → 15:25 호가 → 종가 체결 틱 없이 16:10 시간외 단일가 체결 71,500
        event(book(kst(15, 20, 10), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        event(book(kst(15, 25, 0), asks("70100x60"), bids("70000x400")));
        assertThat(event(trade(kst(16, 10, 0), "71500", 1_000))).isEmpty();
        ex.endOfDay();
        assertThat(state(k)).isEqualTo(OrderState.EXPIRED);
    }

    @Test
    void 종가_단일가_체결은_구간이_끝나고_잠시_뒤에_와도_체결된다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(15, 20, 10), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        event(book(kst(15, 25, 0), asks("70100x60"), bids("70000x400")));
        // 랜덤 엔드로 접수 마감이 늦춰지고 수신이 늦어도 폐장 뒤 60초 안의 첫 체결은 종가 단일가 체결이다
        assertThat(event(trade(kst(15, 30, 59), "70300", 30))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70300", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 평가_가격은_정규장_체결로만_갱신한다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // architect PR #74 R1: 장전 시간외 종가 체결과 단일가 접수 구간 체결 이벤트는 평가 가격을 바꾸지 않는다
        event(trade(kst(8, 35, 0), "69000", 100));
        assertThat(ex.lastTradePrice(S)).isNull();
        event(trade(kst(9, 0, 5), "70200", 5_000));
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70200");
        event(trade(kst(15, 25, 0), "70250", 10));
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70200");
        // 종가 단일가 체결은 갱신하고, 그 뒤 시간외 종가·시간외 단일가 체결은 갱신하지 않는다
        event(trade(kst(15, 30, 1), "70300", 30_000));
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70300");
        event(trade(kst(15, 40, 0), "70300", 500));
        event(trade(kst(16, 10, 0), "71500", 1_000));
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70300");
        assertThat(ex.lastTradePrice(S)).isEqualByComparingTo("70300");
    }

    @Test
    void 그날_첫_이벤트가_개장_한참_뒤면_시가_단일가가_지난_것으로_보고_호가로_체결한다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // architect PR #74 재검토 R4 탐침 P3: 녹화가 11:00에 공백 표시 없이 시작. BOOK-V3은 11:00:05 체결 틱을 시가 단일가로 봐
        // 최우선 매도 호가보다 싼 70,000에 슬리피지 0으로 체결했다
        event(book(kst(11, 0, 0), asks("70100x60", "70200x500"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 100);
        assertThat(event(book(kst(11, 0, 1), asks("70100x60", "70200x500"), bids("70000x400")))).satisfiesExactly(
                a -> assertFill(a, 60, "70100", 1, 0),
                a -> assertFill(a, 40, "70200", 2, 4_000)); // 슬리피지는 첫 최우선 매도 호가 대비
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
        assertThat(event(trade(kst(11, 0, 5), "70000", 3))).isEmpty();
    }

    @Test
    void 그날_첫_이벤트가_개장_직후면_아직_시가_단일가_접수로_본다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // 개장 + 60초 안의 첫 이벤트는 랜덤 엔드 구간일 수 있다
        event(book(kst(9, 0, 59), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(9, 1, 0), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(trade(kst(9, 1, 2), "70200", 5_000))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70200", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 장중_시작은_그날만이고_다음_날은_다시_시가_단일가로_연다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(11, 0, 0), asks("70100x60"), bids("70000x400")));
        ex.endOfDay();
        // 다음 날 장전 호가로는 체결하지 않고 그날 첫 체결을 시가 단일가 체결로 본다
        event(book(kst(24 + 8, 50, 0), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(24 + 9, 0, 40), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(book(kst(24 + 9, 5, 0), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(trade(kst(24 + 9, 5, 1), "70200", 5_000))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70200", 1, 0));
    }

    @Test
    void 장전_시간외_첫_이벤트_뒤_장중에야_이벤트가_오면_시가_단일가가_지난_것으로_본다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // PR #83 후속 ②: 그 종목 그날 첫 이벤트가 08:10 장 밖 체결이고 공백 표시 없이 11:00에야 다음 이벤트가 온다.
        // BOOK-V4는 08:10을 그날 첫 이벤트로 세어 11:00 호가를 시가 단일가 접수로, 11:00:05 체결을 시가 단일가 체결로 봤다
        event(trade(kst(8, 10, 0), "69000", 100));
        event(book(kst(11, 0, 0), asks("70100x60", "70200x500"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 100);
        assertThat(event(book(kst(11, 0, 1), asks("70100x60", "70200x500"), bids("70000x400")))).satisfiesExactly(
                a -> assertFill(a, 60, "70100", 1, 0),
                a -> assertFill(a, 40, "70200", 2, 4_000));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
        assertThat(event(trade(kst(11, 0, 5), "70000", 3))).isEmpty();
    }

    @Test
    void 장전_시간외_이벤트가_있어도_시가_단일가_접수_이벤트가_오면_첫_체결이_시가_단일가_체결이다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(trade(kst(8, 10, 0), "69000", 100));
        event(book(kst(8, 50, 0), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(9, 0, 30), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(trade(kst(9, 0, 31), "70200", 5_000))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70200", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 시가_단일가_접수_구간의_장전_시간외_체결_뒤_장중에야_이벤트가_오면_시가_단일가가_지난_것으로_본다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // architect PR #106 F1: 실제 KRX 장전 시간외(08:30~08:40)는 시가 단일가 접수 구간과 겹친다. 접수 구간에는 정규장 체결이
        // 없으므로 그 체결 이벤트는 그날 첫 정규장 이벤트로 세지 않는다
        event(trade(kst(8, 35, 0), "69000", 100));
        event(book(kst(11, 0, 0), asks("70100x60", "70200x500"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 100);
        assertThat(event(book(kst(11, 0, 1), asks("70100x60", "70200x500"), bids("70000x400")))).satisfiesExactly(
                a -> assertFill(a, 60, "70100", 1, 0),
                a -> assertFill(a, 40, "70200", 2, 4_000));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
        assertThat(event(trade(kst(11, 0, 5), "70000", 3))).isEmpty();
    }

    @Test
    void 시가_단일가_접수_구간의_장전_시간외_체결_뒤_접수_구간_호가가_오면_첫_체결이_시가_단일가_체결이다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(trade(kst(8, 35, 0), "69000", 100));
        event(book(kst(8, 50, 0), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(9, 0, 30), asks("70100x60"), bids("70000x400")))).isEmpty();
        assertThat(event(trade(kst(9, 0, 31), "70200", 5_000))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70200", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 개장_뒤_정확히_60초에_온_첫_이벤트는_시가_단일가가_지난_것으로_본다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        // 경계: 창은 [개장, 개장+60초)다. 09:00:59.999까지는 시가 단일가 접수(개장_직후 테스트), 09:01:00부터 접속 매매
        event(book(kst(9, 1, 0), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(9, 1, 1), asks("70100x60"), bids("70000x400")))).singleElement()
                .satisfies(a -> assertFill(a, 10, "70100", 1, 0));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 종가_단일가_체결_창은_폐장_뒤_정확히_60초에_닫힌다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(trade(kst(15, 19, 0), "70200", 5));
        event(book(kst(15, 20, 10), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        event(book(kst(15, 25, 0), asks("70100x60"), bids("70000x400")));
        assertThat(event(trade(kst(15, 31, 0), "70300", 30))).isEmpty();
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70200");
        ex.endOfDay();
        assertThat(state(k)).isEqualTo(OrderState.EXPIRED);
    }

    @Test
    void 종가_창_안의_두_번째_장_밖_체결은_종가_단일가_체결이_아니다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(15, 20, 10), asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        event(book(kst(15, 25, 0), asks("70100x60"), bids("70000x400")));
        // 종가 단일가 체결 4주만 참여 주문에 쓰고, 같은 창 안 두 번째 체결(15:30:30)로는 남은 6주를 체결하지 않으며 평가도 그대로
        assertThat(event(trade(kst(15, 30, 5), "70300", 4))).singleElement()
                .satisfies(a -> assertFill(a, 4, "70300", 1, 0));
        assertThat(event(trade(kst(15, 30, 30), "70500", 100))).isEmpty();
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70300");
        ex.endOfDay();
        assertThat(state(k)).isEqualTo(OrderState.EXPIRED);
    }

    @Test
    void 종가_단일가_구간이_없는_시장은_폐장_직후_첫_체결을_평가_가격에만_쓴다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), NO_AUCTION);
        // PR #83 후속 ①: 미국 기본 규칙은 종가 단일가 구간이 없어 폐장 직후 종가 크로스 체결이 평가에 쓰이지 않았다
        event(book(kst(15, 29, 50), asks("70300x60"), bids("70200x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.LIMIT, "70200", 10);
        assertThat(event(trade(kst(15, 29, 59), "70250", 5))).isEmpty();
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70250");
        // 종가 크로스 체결: 평가 가격만 갱신하고 주문은 체결하지 않는다(종가 참여 주문 모의 없음, 보수적)
        assertThat(event(trade(kst(15, 30, 1), "70100", 30_000))).isEmpty();
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70100");
        // 그 뒤 창 안 두 번째 체결과 시간외 체결은 평가에 쓰지 않는다
        event(trade(kst(15, 30, 20), "70500", 100));
        event(trade(kst(16, 10, 0), "71500", 1_000));
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70100");
        assertThat(state(k)).isEqualTo(OrderState.ACCEPTED);
    }

    @Test
    void 장_밖_주문은_체결하지_않는다() {
        newExchange(Money.of(10_000_000, Money.KRW), Duration.ofMillis(500), SESSIONS);
        event(book(kst(15, 41, 0), asks("70300x900"), bids("70200x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.MARKET, null, 10);
        assertThat(event(book(kst(15, 41, 5), asks("70300x900"), bids("70200x400")))).isEmpty();
        assertThat(event(trade(kst(15, 42, 0), "70300", 1_000))).isEmpty();
        assertThat(state(k)).isEqualTo(OrderState.ACCEPTED);
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
        // 당일 유효 주문의 장 마감 소멸은 취소 요청이 없었으므로 EXPIRED(ORD-07)다
        assertThat(state(k)).isEqualTo(OrderState.EXPIRED);
        assertThat(state(k).isTerminal()).isTrue();
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
        assertThat(state(k)).isEqualTo(OrderState.CANCELED);
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

    @Test
    void 시세_공백_뒤에는_그_종목_호가를_버려_새_시장가_매수의_기준_가격이_없다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        event(gap(2000));
        // 공백 전 호가로 예약 현금을 잡지 않는다. 체결가도 마지막 체결도 없으니 기준 가격이 없다(D-068)
        assertThat(ack(Side.BUY, OrderType.MARKET, null, 10).state()).isEqualTo(OrderState.REJECTED);
        // 평가는 공백 전 마지막 스냅샷의 최우선 매수 호가를 그대로 쓴다
        assertThat(ex.markPrice(S)).isEqualByComparingTo("70000");
        event(book(3000, asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.MARKET, null, 10);
    }

    @Test
    void 시세_공백은_대기_지정가의_앞선_잔량을_모름으로_되돌리고_다음_호가로_다시_잡는다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        IdempotencyKey k = submit(Side.BUY, OrderType.LIMIT, "70000", 10);
        event(book(2000, asks("70100x60"), bids("70000x400"))); // 대기 시작, 앞선 400
        event(gap(3000));
        // 공백이 없었다면 450 − 400 = 50이 넘쳐 10주 체결. 공백 뒤에는 앞선 잔량을 모르므로 같은 가격 체결로는 체결하지 않는다
        assertThat(event(trade(4000, "70000", 450))).isEmpty();
        event(book(5000, asks("70100x60"), bids("70000x30"))); // 앞선 잔량을 30으로 다시 잡음
        assertThat(event(trade(6000, "70000", 35))).singleElement()
                .satisfies(a -> assertThat(a.fill().quantity()).isEqualTo(5));
        // 지정가보다 유리한 체결은 공백과 무관하게 체결된다
        assertThat(event(trade(7000, "69900", 100))).singleElement()
                .satisfies(a -> assertThat(a.fill().quantity()).isEqualTo(5));
        assertThat(state(k)).isEqualTo(OrderState.FILLED);
    }

    @Test
    void 다른_종목의_시세_공백은_이_종목_체결에_영향이_없다() {
        event(book(1000, asks("70100x60"), bids("70000x400")));
        submit(Side.BUY, OrderType.LIMIT, "70000", 10);
        event(book(2000, asks("70100x60"), bids("70000x400")));
        Instant t = T0.plusMillis(3000);
        event(new DataGap(Symbol.krx("000660"), ++seq, t, t, t.minusSeconds(1), t, DataGap.GapReason.RECORDER_RESTART));
        assertThat(event(trade(4000, "70000", 450))).singleElement()
                .satisfies(a -> assertThat(a.fill().quantity()).isEqualTo(10));
    }

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

    private DataGap gap(long ms) {
        Instant t = T0.plusMillis(ms);
        return new DataGap(S, ++seq, t, t, t.minusSeconds(1), t, DataGap.GapReason.DISCONNECTED);
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
