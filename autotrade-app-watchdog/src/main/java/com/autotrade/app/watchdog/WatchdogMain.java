package com.autotrade.app.watchdog;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.ProcessStartup;
import java.time.Clock;

/**
 * 감시·킬스위치(D-013 ④). 1단계는 설정 로딩까지만 하는 진입점이다. 하트비트 감시와 긴급 명령은 risk-ops 작업.
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class WatchdogMain {

    static final String PROCESS = "watchdog";

    private WatchdogMain() {}

    public static void main(String[] args) {
        System.exit(run(ConfigPaths.defaultLocation(), Clock.systemUTC()));
    }

    /** 기동 절차. 종료 코드를 돌려준다(0: 정상). */
    static int run(ConfigPaths paths, Clock clock) {
        ProcessStartup.start(PROCESS, paths, clock);
        return 0;
    }
}
