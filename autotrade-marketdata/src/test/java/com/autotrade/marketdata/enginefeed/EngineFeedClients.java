package com.autotrade.marketdata.enginefeed;

import java.nio.file.Path;
import java.time.Duration;

/** 다른 패키지 시험이 짧은 재시도·인사 시간으로 받는 쪽을 만드는 입구. */
public final class EngineFeedClients {

    private EngineFeedClients() {}

    public static EngineFeedClient forTest(Path socket, String boot, EngineFeedClient.Listener listener, Duration retry,
            Duration helloTimeout) {
        return new EngineFeedClient(socket, boot, listener, retry, helloTimeout);
    }
}
