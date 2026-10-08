package com.autotrade.sim.testing;

import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.domain.order.OrderIntent;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.StrategyFactory;
import com.autotrade.strategy.StrategyProfile;
import com.autotrade.strategy.TradeSignal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 골든 테스트용 고정 전략. 실제 매매 기법이 아니라 체결 규칙을 골고루 지나가게 만든 것이다.
 * <ul>
 *   <li>{@code mkt-hold}: 첫 호가에 시장가 매수, 다음 거래일 첫 이벤트에 시장가 전량 매도</li>
 *   <li>{@code limit-maker}: 첫 호가에 지정가 매수 대기, 보유가 생기면 지정가 매도</li>
 *   <li>{@code probe}: 첫 호가에 {@code symbol} 시장가 매수 시도(블랙리스트 확인용)</li>
 *   <li>{@code boom}: {@code at}번째 이벤트에서 예외(계좌 격리 확인용)</li>
 * </ul>
 */
public final class FixtureStrategies implements StrategyFactory {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Override
    public Strategy create(StrategyProfile profile, long runSeed) {
        var p = profile.parameters();
        return switch (profile.id()) {
            case "mkt-hold" -> new MarketHold(Symbol.krx(p.get("symbol")), Long.parseLong(p.get("qty")));
            case "limit-maker" -> new LimitMaker(Symbol.krx(p.get("symbol")), Long.parseLong(p.get("qty")),
                    new BigDecimal(p.get("buy-price")), new BigDecimal(p.get("sell-price")));
            case "probe" -> new Probe(Symbol.krx(p.get("symbol")), Symbol.krx(p.get("trigger")));
            case "boom" -> new Boom(Integer.parseInt(p.get("at")));
            default -> throw new IllegalArgumentException("모르는 프로필: " + profile.id());
        };
    }

    private static final class MarketHold implements Strategy {
        private final Symbol symbol;
        private final long qty;
        private LocalDate boughtOn;
        private boolean sold;

        MarketHold(Symbol symbol, long qty) {
            this.symbol = symbol;
            this.qty = qty;
        }

        @Override
        public List<TradeSignal> onEvent(MarketEvent event, StrategyContext ctx) {
            if (!event.symbol().equals(symbol)) {
                return List.of();
            }
            LocalDate today = event.exchangeTime().atZone(SEOUL).toLocalDate();
            if (boughtOn == null && event instanceof OrderBookSnapshot) {
                boughtOn = today;
                return List.of(TradeSignal.marketBuy(symbol, qty));
            }
            long held = ctx.account().quantityOf(symbol);
            if (!sold && boughtOn != null && today.isAfter(boughtOn) && held > 0 && !ctx.hasOpenOrder(symbol)) {
                sold = true;
                return List.of(TradeSignal.marketSell(symbol, OrderIntent.EXIT, held));
            }
            return List.of();
        }
    }

    private static final class LimitMaker implements Strategy {
        private final Symbol symbol;
        private final long qty;
        private final BigDecimal buyPrice;
        private final BigDecimal sellPrice;
        private boolean bought;
        private boolean sellPlaced;

        LimitMaker(Symbol symbol, long qty, BigDecimal buyPrice, BigDecimal sellPrice) {
            this.symbol = symbol;
            this.qty = qty;
            this.buyPrice = buyPrice;
            this.sellPrice = sellPrice;
        }

        @Override
        public List<TradeSignal> onEvent(MarketEvent event, StrategyContext ctx) {
            if (!event.symbol().equals(symbol)) {
                return List.of();
            }
            if (!bought && event instanceof OrderBookSnapshot) {
                bought = true;
                return List.of(TradeSignal.limitBuy(symbol, buyPrice, qty));
            }
            long held = ctx.account().quantityOf(symbol);
            if (!sellPlaced && held == qty) {
                sellPlaced = true;
                return List.of(TradeSignal.limitSell(symbol, OrderIntent.EXIT, sellPrice, held));
            }
            return List.of();
        }
    }

    private static final class Probe implements Strategy {
        private final Symbol target;
        private final Symbol trigger;
        private boolean done;

        Probe(Symbol target, Symbol trigger) {
            this.target = target;
            this.trigger = trigger;
        }

        @Override
        public List<TradeSignal> onEvent(MarketEvent event, StrategyContext ctx) {
            if (!done && event.symbol().equals(trigger) && event instanceof OrderBookSnapshot) {
                done = true;
                return List.of(TradeSignal.marketBuy(target, 1));
            }
            return List.of();
        }
    }

    private static final class Boom implements Strategy {
        private final int at;
        private int seen;

        Boom(int at) {
            this.at = at;
        }

        @Override
        public List<TradeSignal> onEvent(MarketEvent event, StrategyContext ctx) {
            if (++seen == at) {
                throw new IllegalStateException("고의 실패 at " + at);
            }
            return List.of();
        }
    }
}
