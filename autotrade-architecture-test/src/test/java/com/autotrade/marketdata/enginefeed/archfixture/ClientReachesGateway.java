package com.autotrade.marketdata.enginefeed.archfixture;

import com.autotrade.marketdata.enginefeed.server.EngineFeedServer;
import com.autotrade.marketdata.feed.FeedServer;
import com.autotrade.marketdata.recording.JournalWriter;

/**
 * 규칙 위반 픽스처: 엔진 받는 쪽 패키지가 서버·실험실 전달·녹화 쓰기를 쓴다(엔진이 받는 쪽을 거쳐 게이트웨이 부품에 닿는 길).
 * 프로덕션 코드가 아니며 {@code EngineFeedBoundaryTest}만 쓴다.
 */
public final class ClientReachesGateway {

    private ClientReachesGateway() {}

    static void reach(EngineFeedServer server, FeedServer lab, JournalWriter writer) throws java.io.IOException {
        server.close();
        lab.close();
        writer.close();
    }
}
