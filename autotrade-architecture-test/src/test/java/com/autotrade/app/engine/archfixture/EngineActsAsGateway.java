package com.autotrade.app.engine.archfixture;

import com.autotrade.marketdata.enginefeed.EngineFeedClient;
import com.autotrade.marketdata.enginefeed.EngineFeedProtocol;
import com.autotrade.marketdata.enginefeed.server.EngineFeedServer;
import com.autotrade.marketdata.feed.FeedClient;
import com.autotrade.marketdata.live.StreamingMarketDataSource;
import com.autotrade.marketdata.recording.MarketEventRecorder;
import com.autotrade.marketdata.recording.RecordingCodec;

/**
 * 규칙 위반 픽스처: 실전 엔진이 게이트웨이 행세를 한다(엔진 전달 서버·실험실 전달·증권사 WebSocket 소스·녹화 쓰기). 녹화 형식
 * ({@link RecordingCodec})과 엔진 받는 쪽·줄 형식은 허용이다. 프로덕션 코드가 아니며 {@code EngineFeedBoundaryTest}만 쓴다.
 */
public final class EngineActsAsGateway {

    private EngineActsAsGateway() {}

    static void gatewayParts(EngineFeedServer server, FeedClient labFeed, StreamingMarketDataSource ws,
            MarketEventRecorder recorder) {
        server.close();
        labFeed.close();
        ws.close();
        recorder.close();
    }

    static String allowed(EngineFeedClient client, RecordingCodec codec) {
        client.close();
        return codec.encode(1, "x", null) + EngineFeedProtocol.NAME;
    }
}
