package com.autotrade.sim.account;

import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.account.Position;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.Side;
import com.autotrade.sim.exchange.Fill;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 가상 계좌(SIM-08): 시드, 현금, 포지션, 일일 거래 건수, 일일·주간 실현 손익 누적, 체결 원장.
 *
 * <p>Run 하나 전용이고 스레드 안전하지 않다(한 Run은 한 스레드에서만 돈다). 다른 계좌와 아무것도 공유하지 않는다.
 * 금액은 모두 계좌 통화 {@link Money}. 보유 원가는 매수 비용을 포함하고, 매도 시 평균 원가로 덜어 낸다.
 */
public final class VirtualAccount {

    /** 원가 배분 계산 자릿수(DB NUMERIC(20,4)와 같다). */
    static final int COST_SCALE = 4;

    private final AccountId id;
    private final Money seed;
    private Money cash;
    private final TreeMap<Symbol, Holding> holdings = new TreeMap<>();
    private final List<AppliedFill> ledger = new ArrayList<>();
    private final List<RoundTrip> roundTrips = new ArrayList<>();
    private final TreeMap<LocalDate, Money> dailyRealized = new TreeMap<>();
    private final TreeMap<LocalDate, Set<String>> dailyOrders = new TreeMap<>();
    private int lotCounter;

    public VirtualAccount(AccountId id, Money seed) {
        this.id = Objects.requireNonNull(id, "id");
        this.seed = Objects.requireNonNull(seed, "seed");
        if (seed.signum() <= 0) {
            throw new IllegalArgumentException("시드머니는 0보다 커야 합니다: " + seed);
        }
        this.cash = seed;
    }

    public AccountId id() {
        return id;
    }

    public Money seed() {
        return seed;
    }

    public Money cash() {
        return cash;
    }

    public Currency currency() {
        return seed.currency();
    }

    public long quantityOf(Symbol symbol) {
        Holding h = holdings.get(symbol);
        return h == null ? 0L : h.quantity;
    }

    /** 체결을 반영한다. 매수 현금 부족이나 보유 초과 매도는 거래소가 막아야 하는 프로그래밍 오류다. */
    public AppliedFill apply(Fill fill) {
        Objects.requireNonNull(fill, "fill");
        Money notional = fill.notional();
        Money fees = fill.fees().total();
        if (!notional.currency().equals(currency())) {
            throw new IllegalArgumentException("계좌 통화와 체결 통화가 다릅니다: " + notional);
        }
        AppliedFill applied;
        if (fill.side() == Side.BUY) {
            Money cost = notional.plus(fees);
            if (cost.compareTo(cash) > 0) {
                throw new IllegalStateException("현금보다 큰 매수 체결입니다: " + fill.key());
            }
            cash = cash.minus(cost);
            Holding h = holdings.computeIfAbsent(fill.symbol(), s -> new Holding(
                    id + "-" + s.code() + "-" + (++lotCounter), fill.exchangeDate(), currency()));
            h.quantity += fill.quantity();
            h.costBasis = h.costBasis.plus(cost);
            applied = new AppliedFill(fill, h.lotId, null);
        } else {
            Holding h = holdings.get(fill.symbol());
            if (h == null || h.quantity < fill.quantity()) {
                throw new IllegalStateException("보유보다 많은 매도 체결입니다: " + fill.key());
            }
            Money costPortion = h.quantity == fill.quantity()
                    ? h.costBasis
                    : share(h.costBasis, fill.quantity(), h.quantity);
            Money proceeds = notional.minus(fees);
            Money realized = proceeds.minus(costPortion);
            cash = cash.plus(proceeds);
            h.quantity -= fill.quantity();
            h.costBasis = h.costBasis.minus(costPortion);
            h.realized = h.realized.plus(realized);
            dailyRealized.merge(fill.exchangeDate(), realized, Money::plus);
            applied = new AppliedFill(fill, h.lotId, realized);
            if (h.quantity == 0) {
                holdings.remove(fill.symbol());
                roundTrips.add(new RoundTrip(h.lotId, fill.symbol(), h.entryDate, fill.exchangeDate(), h.realized));
            }
        }
        ledger.add(applied);
        dailyOrders.computeIfAbsent(fill.exchangeDate(), d -> new HashSet<>()).add(fill.key().toString());
        return applied;
    }

    /** 리스크 검증·전략 입력용 스냅샷. 평균 단가는 매수 비용을 포함한 원가 기준이다. */
    public AccountSnapshot snapshot(Instant asOf) {
        TreeMap<Symbol, Position> positions = new TreeMap<>();
        for (Map.Entry<Symbol, Holding> e : holdings.entrySet()) {
            Holding h = e.getValue();
            Money avg = share(h.costBasis, 1, h.quantity);
            positions.put(e.getKey(), new Position(e.getKey(), h.quantity, avg));
        }
        return new AccountSnapshot(id, asOf, cash, positions);
    }

    /**
     * 평가금액 = 현금 + Σ 보유 수량 × 평가 가격. 평가 가격을 모르는 종목은 보유 원가로 평가한다.
     *
     * @param markPrice 종목 → 평가 가격. 모르면 null
     */
    public Money equity(Function<Symbol, BigDecimal> markPrice) {
        Money total = cash;
        for (Map.Entry<Symbol, Holding> e : holdings.entrySet()) {
            BigDecimal mark = markPrice.apply(e.getKey());
            total = total.plus(mark == null
                    ? e.getValue().costBasis
                    : new Money(mark.multiply(BigDecimal.valueOf(e.getValue().quantity)), currency()));
        }
        return total;
    }

    /** 보유 원가 합계(비용 포함). */
    public Money costBasisOf(Symbol symbol) {
        Holding h = holdings.get(symbol);
        return h == null ? Money.zero(currency()) : h.costBasis;
    }

    /** 그 날 체결이 하나 이상 난 주문 수(SIM-08 일일 거래 건수). */
    public int tradeCount(LocalDate date) {
        Set<String> keys = dailyOrders.get(date);
        return keys == null ? 0 : keys.size();
    }

    /** 그 날 실현 손익 합계(비용 차감 후). 손실은 음수. */
    public Money realizedOn(LocalDate date) {
        return dailyRealized.getOrDefault(date, Money.zero(currency()));
    }

    /** 그 날이 속한 주(월요일 시작, 거래소 현지 날짜)의 그 날까지 실현 손익 합계. */
    public Money realizedWeekTo(LocalDate date) {
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Money sum = Money.zero(currency());
        for (Money m : dailyRealized.subMap(monday, true, date, true).values()) {
            sum = sum.plus(m);
        }
        return sum;
    }

    public List<AppliedFill> ledger() {
        return List.copyOf(ledger);
    }

    public List<RoundTrip> roundTrips() {
        return List.copyOf(roundTrips);
    }

    /** 보유 수량(종목 순). */
    public SortedMap<Symbol, Long> quantities() {
        TreeMap<Symbol, Long> out = new TreeMap<>();
        holdings.forEach((s, h) -> out.put(s, h.quantity));
        return out;
    }

    /**
     * 가상 계좌 불변식(SIM-15)을 원장에서 다시 계산해 확인한다. 어기면 {@link IllegalStateException}으로 Run을 멈춘다.
     */
    public void verifyInvariants() {
        LedgerCheck.verify(seed, ledger, cash, quantities());
    }

    /** amount × part / whole, 소수 {@link #COST_SCALE}자리 HALF_EVEN. */
    private static Money share(Money amount, long part, long whole) {
        return new Money(amount.amount().multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), COST_SCALE, RoundingMode.HALF_EVEN), amount.currency());
    }

    private static final class Holding {
        private final String lotId;
        private final LocalDate entryDate;
        private long quantity;
        private Money costBasis;
        private Money realized;

        Holding(String lotId, LocalDate entryDate, Currency currency) {
            this.lotId = lotId;
            this.entryDate = entryDate;
            this.costBasis = Money.zero(currency);
            this.realized = Money.zero(currency);
        }
    }
}
