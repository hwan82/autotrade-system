package com.autotrade.config;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Objects;

/**
 * Spring 없이 도는 프로세스(실전 엔진, 시세 게이트웨이, 감시)의 공통 기동 절차: 설정 로딩과 기동 로그.
 * 비밀값은 로그에 나오지 않는다(경고는 경로와 규칙만 담는다).
 */
public final class ProcessStartup {

    private ProcessStartup() {}

    public static LoadedConfig start(String processName, ConfigPaths paths, Clock clock) {
        Objects.requireNonNull(processName, "processName");
        Logger log = System.getLogger("com.autotrade." + processName);
        LoadedConfig loaded = new ConfigLoader().load(paths);
        if (!loaded.warning().isEmpty()) {
            log.log(Level.WARNING, "설정 파일 문제: {0}", loaded.warning());
        }
        log.log(Level.INFO, "{0} 기동 {1}, 설정 {2} ({3})",
                processName, clock.instant(), loaded.source(), paths.runtimeFile().getParent());
        return loaded;
    }
}
