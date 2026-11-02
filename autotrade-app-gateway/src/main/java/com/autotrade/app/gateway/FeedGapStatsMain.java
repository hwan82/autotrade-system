package com.autotrade.app.gateway;

import com.autotrade.config.ConfigPaths;
import java.nio.file.Path;
import java.time.Clock;

/**
 * 시세 수신 간격 측정 진입점(ADR-016 G0, D-147). 시스템 시계와 설정 폴더는 여기서만 만들고 {@link FeedGapStats}에 넣는다
 * (ArchUnit {@code NO_SYSTEM_TIME}). 사용법과 종료 코드는 {@link FeedGapStats}.
 */
public final class FeedGapStatsMain {

    private FeedGapStatsMain() {}

    public static void main(String[] args) {
        Path config = ConfigPaths.defaultLocation().runtimeFile().toAbsolutePath().getParent();
        System.exit(FeedGapStats.run(args, Clock.systemUTC(), config, System.out, System.err));
    }
}
