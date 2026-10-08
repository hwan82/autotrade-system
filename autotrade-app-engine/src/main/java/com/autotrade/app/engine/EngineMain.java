package com.autotrade.app.engine;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.ProcessStartup;
import java.time.Clock;

/**
 * 실전 매매 엔진(D-013 ①). 1단계는 설정 로딩까지만 하는 진입점이다. 계좌별 워커, 주문 큐, RealTradingGuard는 risk-ops 작업(7단계).
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class EngineMain {

    static final String PROCESS = "engine";

    private EngineMain() {}

    public static void main(String[] args) {
        System.exit(run(ConfigPaths.defaultLocation(), Clock.systemUTC()));
    }

    /** 기동 절차. 종료 코드를 돌려준다(0: 정상). */
    static int run(ConfigPaths paths, Clock clock) {
        ProcessStartup.start(PROCESS, paths, clock);
        return 0;
    }
}
