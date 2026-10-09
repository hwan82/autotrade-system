package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.Money;
import com.autotrade.domain.account.AccountId;
import com.autotrade.domain.account.AccountSnapshot;
import com.autotrade.domain.account.Position;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.PriceLevel;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.market.TradeTick;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.TradeSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 전략 단위 테스트용 시세 테이프와 가짜 계좌. 체결은 테스트가 {@link #hold}로 직접 정한다(가상 거래소 없이 전략 판단만 본다).
 */
final class Tape {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Symbol A = Symbol.krx("900001");
    static final MarketRules KRX = new MarketRules() {
        @Override
        public Market market() {
            return Market.KRX;
        }

        @Override
        public ZoneId zone() {
            return SEOUL;
        }

        @Override
        public boolean isRegularSession(Instant instant) {
            return true;
        }

        @Override
        public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
            return BigDecimal.TEN;
        }
    };

    private final Strategy strategy;
    private LocalDate date = LocalDate.of(2026, 3, 2);
    private long seq;
    private Money cash = Money.of(10_000_000, Money.KRW);
    private final TreeMap<Symbol, Position> positions = new TreeMap<>();
    private final Set<Symbol> open = new HashSet<>();

    Tape(Strategy strategy) {
        this.strategy = strategy;
    }

    Tape day(LocalDate d) {
        date = d;
        return this;
    }

    /** 보유를 정한다(수량 0이면 청산). 평균 단가는 원 단위. */
    Tape hold(Symbol s, long qty, long avg) {
        if (qty == 0) {
            positions.remove(s);
        } else {
            positions.put(s, new Position(s, qty, Money.of(avg, Money.KRW)));
        }
        return this;
    }

    Tape cash(long won) {
        cash = Money.of(won, Money.KRW);
        return this;
    }

    Tape openOrder(Symbol s, boolean isOpen) {
        if (isOpen) {
            open.add(s);
        } else {
            open.remove(s);
        }
        return this;
    }

    List<TradeSignal> trade(String time, long price, long qty) {
        Instant t = at(time);
        return feed(new TradeTick(A, ++seq, t, t, BigDecimal.valueOf(price), qty));
    }

    List<TradeSignal> book(String time, long bid, long ask, long bidQty, long askQty) {
        Instant t = at(time);
        return feed(new OrderBookSnapshot(A, ++seq, t, t,
                List.of(new PriceLevel(BigDecimal.valueOf(ask), askQty)),
                List.of(new PriceLevel(BigDecimal.valueOf(bid), bidQty))));
    }

    private Instant at(String time) {
        return date.atTime(LocalTime.parse(time)).atZone(SEOUL).toInstant();
    }

    private List<TradeSignal> feed(MarketEvent e) {
        AccountSnapshot snap = new AccountSnapshot(new AccountId("T-A01"), e.receivedAt(), cash, positions);
        Instant now = e.receivedAt();
        return strategy.onEvent(e, new StrategyContext() {
            @Override
            public Instant now() {
                return now;
            }

            @Override
            public AccountSnapshot account() {
                return snap;
            }

            @Override
            public boolean hasOpenOrder(Symbol symbol) {
                return open.contains(symbol);
            }
        });
    }
}
