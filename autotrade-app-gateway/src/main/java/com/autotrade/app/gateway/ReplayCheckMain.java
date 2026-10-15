package com.autotrade.app.gateway;

import com.autotrade.config.ConfigPaths;
import java.nio.file.Path;
import java.time.Clock;

/**
 * 리플레이 점검 진입점(D-085). 시스템 시계와 설정 폴더는 여기서만 만들고 {@link ReplayCheck}에 넣는다(ArchUnit {@code NO_SYSTEM_TIME}).
 * 사용법과 종료 코드는 {@link ReplayCheck}.
 */
public final class ReplayCheckMain {

    private ReplayCheckMain() {}

    public static void main(String[] args) {
        Path config = ConfigPaths.defaultLocation().runtimeFile().toAbsolutePath().getParent();
        System.exit(ReplayCheck.run(args, Clock.systemUTC(), config, System.out, System.err));
    }
}
