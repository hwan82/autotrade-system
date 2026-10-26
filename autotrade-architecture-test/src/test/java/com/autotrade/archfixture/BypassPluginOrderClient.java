package com.autotrade.archfixture;

import com.autotrade.broker.BrokerCredentials;
import com.autotrade.broker.kis.KisTokenClient;
import com.autotrade.broker.kis.order.KisAccountNumber;
import com.autotrade.broker.kis.order.KisOrderClient;
import com.autotrade.broker.kis.order.KisSession;
import com.autotrade.domain.order.OrderRequest;
import com.autotrade.marketdata.live.HttpPort;
import java.time.Clock;

/**
 * 위반 예제: 플러그인 밖에서 한국투자 주문 클라이언트를 직접 만들어 주문을 보낸다. RealTradingGuard와 출구 팩토리를 모두
 * 건너뛰는 경로다. {@code PLUGIN_ORDER_CODE_STAYS_IN_PLUGIN}이 잡아야 한다(ADR-002 6장, ADR-003 6장, D-145).
 */
public final class BypassPluginOrderClient {

    private BypassPluginOrderClient() {
    }

    public static Object send(KisSession session, KisAccountNumber account, OrderRequest request) {
        return new KisOrderClient(session, account).submit(request);
    }

    /** 위반 예제: 플러그인 밖에서 실전 앱키 토큰 발급기를 직접 만든다(재발급 60초 하한을 건너뜀). */
    public static Object token(BrokerCredentials keys, HttpPort http, Clock clock) {
        return KisTokenClient.fetcher(http, keys, clock);
    }
}
