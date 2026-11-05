package com.autotrade.app.lab.archfixture;

import com.autotrade.marketdata.enginefeed.EngineFeedClient;
import com.autotrade.marketdata.enginefeed.server.EngineFeedServer;

/**
 * 규칙 위반 픽스처: 실험실이 실전 엔진 행세(엔진 받는 쪽)와 게이트웨이 행세(엔진 전달 서버)를 한다. 프로덕션 코드가 아니며
 * {@code EngineFeedBoundaryTest}만 쓴다.
 */
public final class LabActsAsEngine {

    private LabActsAsEngine() {}

    static void both(EngineFeedClient client, EngineFeedServer server) {
        client.close();
        server.close();
    }
}
