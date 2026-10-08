package com.autotrade.sim.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.IdempotencyKey;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.cost.CostModel;
import com.autotrade.sim.cost.CostModels;
import com.autotrade.sim.cost.KrxBoard;
import com.autotrade.sim.exchange.Fill;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** SIM-08 가상 계좌, SIM-15 불변식. */
class VirtualAccountTest {

    private static final Symbol S = Symbol.krx("005930");
    private static final CostModel KR = CostModels.krDefault(s -> KrxBoard.KOSPI);
    private static final LocalDate MON = LocalDate.of(2026, 3, 2);
    private static final LocalDate TUE = LocalDate.of(2026, 3, 3);

    @Test
    void 매수_비용을_원가에_넣고_매도_시_평균_원가로_실현손익을_낸다() {
        VirtualAccount a = new VirtualAccount(new AccountId("A"), Money.of(10_000_000, Money.KRW));
        a.apply(fill(Side.BUY, 1, MON, "70000", 100)); // 7,000,000 + 1,050
        AppliedFill part = a.apply(fill(Side.SELL, 1, TUE, "71000", 40));
        // 매도 2,840,000: 수수료 426, 거래세 1,420, 농특세 4,260 → 2,833,894. 원가 7,001,050 × 0.4 = 2,800,420
        assertThat(part.realizedPnl()).isEqualTo(Money.of(33_474, Money.KRW));
        AppliedFill rest = a.apply(fill(Side.SELL, 2, TUE, "69000", 60));
        // 4,140,000 − (621 + 2,070 + 6,210) = 4,131,099. 남은 원가 4,200,630
        assertThat(rest.realizedPnl()).isEqualTo(Money.of(-69_531, Money.KRW));

        assertThat(a.roundTrips()).singleElement().satisfies(rt -> {
            assertThat(rt.pnl()).isEqualTo(Money.of(-36_057, Money.KRW));
            assertThat(rt.entryDate()).isEqualTo(MON);
            assertThat(rt.exitDate()).isEqualTo(TUE);
            assertThat(rt.lotId()).isEqualTo(part.lotId()).isEqualTo(rest.lotId());
        });
        assertThat(a.cash()).isEqualTo(Money.of(10_000_000 - 36_057, Money.KRW));
        assertThat(a.realizedOn(TUE)).isEqualTo(Money.of(-36_057, Money.KRW));
        assertThat(a.realizedWeekTo(TUE)).isEqualTo(Money.of(-36_057, Money.KRW));
        assertThat(a.realizedWeekTo(LocalDate.of(2026, 3, 9))).isEqualTo(Money.zero(Money.KRW));
        assertThat(a.tradeCount(TUE)).isEqualTo(2);
        assertThat(a.tradeCount(MON)).isEqualTo(1);
        a.verifyInvariants();
    }

    @Test
    void 스냅샷과_평가금액() {
        VirtualAccount a = new VirtualAccount(new AccountId("A"), Money.of(10_000_000, Money.KRW));
        a.apply(fill(Side.BUY, 1, MON, "70000", 100));
        var snap = a.snapshot(Instant.EPOCH);
        assertThat(snap.quantityOf(S)).isEqualTo(100);
        assertThat(snap.positions().get(S).averagePrice()).isEqualTo(Money.of("70010.5", Money.KRW));
        assertThat(a.equity(s -> new BigDecimal("71000"))).isEqualTo(Money.of(2_998_950 + 7_100_000, Money.KRW));
        // 평가 가격을 모르면 원가로
        assertThat(a.equity(s -> null)).isEqualTo(Money.of(10_000_000, Money.KRW));
    }

    @Test
    void 계좌끼리_상태를_공유하지_않는다() {
        VirtualAccount a = new VirtualAccount(new AccountId("A"), Money.of(10_000_000, Money.KRW));
        VirtualAccount b = new VirtualAccount(new AccountId("B"), Money.of(10_000_000, Money.KRW));
        a.apply(fill(Side.BUY, 1, MON, "70000", 100));
        assertThat(b.cash()).isEqualTo(Money.of(10_000_000, Money.KRW));
        assertThat(b.quantityOf(S)).isZero();
        assertThat(b.ledger()).isEmpty();
    }

    @Test
    void 보유보다_많은_매도와_현금보다_큰_매수는_프로그래밍_오류다() {
        VirtualAccount a = new VirtualAccount(new AccountId("A"), Money.of(1_000_000, Money.KRW));
        assertThatThrownBy(() -> a.apply(fill(Side.SELL, 1, MON, "70000", 1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> a.apply(fill(Side.BUY, 1, MON, "70000", 100))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 원장과_계좌가_어긋나면_불변식_검사가_잡는다() {
        VirtualAccount a = new VirtualAccount(new AccountId("A"), Money.of(10_000_000, Money.KRW));
        AppliedFill f = a.apply(fill(Side.BUY, 1, MON, "70000", 100));
        Money seed = Money.of(10_000_000, Money.KRW);
        TreeMap<Symbol, Long> qty = new TreeMap<>();
        qty.put(S, 100L);
        LedgerCheck.verify(seed, List.of(f), a.cash(), qty);
        assertThatThrownBy(() -> LedgerCheck.verify(seed, List.of(f), a.cash().plus(Money.of(1, Money.KRW)), qty))
                .hasMessageContaining("SIM-15");
        qty.put(S, 99L);
        assertThatThrownBy(() -> LedgerCheck.verify(seed, List.of(f), a.cash(), qty)).hasMessageContaining("SIM-15");
        assertThatThrownBy(() -> LedgerCheck.verify(Money.of(1_000, Money.KRW), List.of(f),
                Money.of(1_000 - 7_001_050, Money.KRW), new TreeMap<>(java.util.Map.of(S, 100L))))
                .hasMessageContaining("음수");
    }

    private static Fill fill(Side side, int seq, LocalDate date, String price, long qty) {
        BigDecimal p = new BigDecimal(price);
        Instant t = date.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        return new Fill(IdempotencyKey.of(date, S, side, seq), 1, S, side,
                side == Side.BUY ? OrderIntent.ENTRY : OrderIntent.EXIT, qty, p, KR.fees(side, S, date, p, qty),
                Money.zero(Money.KRW), 1, 2, t, t, date);
    }
}
