package com.autotrade.strategy.catalog;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.MarketEvent;
import com.autotrade.domain.market.OrderBookSnapshot;
import com.autotrade.domain.market.Symbol;
import com.autotrade.strategy.Strategy;
import com.autotrade.strategy.StrategyContext;
import com.autotrade.strategy.TradeSignal;
import com.autotrade.strategy.profile.ProfileParams;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 매수 후 보유 기준 전략(logic {@code buy-and-hold}): 종목마다 첫 호가에서 (첫 현금 × (1 − 현금 여유)) / 종목 수만큼
 * 시장가로 사고 끝까지 들고 간다. 같은 유니버스·같은 비용으로 "아무 판단도 하지 않은 결과"를 만든다.
 * 종목 수는 데이터에 몇 종목이 나올지 미리 알아야 해서 프로필 파라미터({@code sizing.slots})로 받는다.
 */
final class BuyAndHold implements Strategy {

    private final MarketRules market;
    private final int slots;
    private final BigDecimal cashBuffer;
    private final Set<Symbol> attempted = new HashSet<>();
    private BigDecimal perSlot;

    BuyAndHold(MarketRules market, ProfileParams p) {
        this.market = market;
        this.slots = p.count("sizing.slots", 1, 100);
        this.cashBuffer = p.percent("sizing.cash-buffer-pct", "0", "50");
    }

    @Override
    public List<TradeSignal> onEvent(MarketEvent event, StrategyContext ctx) {
        if (perSlot == null) {
            BigDecimal investable = ctx.account().cash().amount().multiply(BigDecimal.ONE.subtract(cashBuffer));
            perSlot = investable.divide(BigDecimal.valueOf(slots), 0, RoundingMode.DOWN);
        }
        if (!(event instanceof OrderBookSnapshot b) || b.asks().isEmpty() || attempted.size() >= slots
                || !attempted.add(event.symbol())) {
            return List.of();
        }
        BigDecimal ask = b.asks().get(0).price();
        // 비용·호가 여유 0.3% `[가정]`
        long qty = perSlot.divide(ask.multiply(new BigDecimal("1.003")), 0, RoundingMode.DOWN).longValueExact();
        qty -= qty % market.quantityStep();
        return qty <= 0 ? List.of() : List.of(TradeSignal.marketBuy(event.symbol(), qty));
    }
}
