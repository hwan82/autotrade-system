package com.autotrade.app.gateway.archfixture;

import com.autotrade.marketdata.enginefeed.EngineFeedClient;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol;
import com.autotrade.marketdata.enginefeed.server.EngineFeedServer;

/**
 * 규칙 위반 픽스처: 게이트웨이가 엔진 받는 쪽을 쓴다. 엔진 전달 서버와 줄 형식(배정 이유)은 게이트웨이에 허용이다. 프로덕션 코드가
 * 아니며 {@code EngineFeedBoundaryTest}만 쓴다.
 */
public final class GatewayActsAsEngine {

    private GatewayActsAsEngine() {}

    static void engineSide(EngineFeedClient client) {
        client.close();
    }

    static void allowed(EngineFeedServer server) {
        server.assignment(java.util.Map.of(), java.util.Map.of(
                com.autotrade.domain.market.Symbol.krx("005930"), EngineFeedProtocol.Unserved.CAPACITY));
    }
}
