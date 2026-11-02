package com.autotrade.app.engine.archfixture;

import com.autotrade.broker.BrokerPlugin;
import com.autotrade.domain.market.Market;
import com.autotrade.marketdata.MarketDataSource;
import com.autotrade.marketdata.polling.PollingQuoteSource;
import java.util.Optional;
import java.util.Set;

/**
 * 규칙 위반 픽스처: 실전 엔진이 증권사 플러그인에서 시세 소스를 받아 직접 구독한다(증권사 WebSocket을 엶, PR #257 QA S4·Q3).
 * 보호 폴링({@link BrokerPlugin#pollingQuotes()})은 허용이다. 프로덕션 코드가 아니며 {@code EngineFeedBoundaryTest}만 쓴다.
 */
public final class EngineOpensBrokerStream {

    private EngineOpensBrokerStream() {}

    static Optional<MarketDataSource> stream(BrokerPlugin plugin) {
        return plugin.marketData(Market.KRX);
    }

    static Optional<PollingQuoteSource> allowed(BrokerPlugin plugin) {
        return plugin.pollingQuotes();
    }
}
