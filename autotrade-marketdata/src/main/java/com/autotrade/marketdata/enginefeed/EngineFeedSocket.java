package com.autotrade.marketdata.enginefeed;

import java.nio.file.Path;

/**
 * 실전 엔진 시세 전달 소켓 위치(ADR-016 2.2). 설정 폴더 아래 {@code run/engine-feed.sock}(Unix 도메인 소켓). 실험실 소켓
 * ({@code run/market-feed.sock})과 다른 소켓·다른 서버이며, 받는 쪽은 실전 엔진 하나뿐이다. 폴더 700·소켓 600은 서버가 맞춘다.
 */
public final class EngineFeedSocket {

    public static final String DIR = "run";
    public static final String FILE = "engine-feed.sock";

    private EngineFeedSocket() {}

    /** @param configDir 설정 폴더({@code runtime-config.json}이 있는 곳) */
    public static Path path(Path configDir) {
        return configDir.toAbsolutePath().resolve(DIR).resolve(FILE);
    }
}
