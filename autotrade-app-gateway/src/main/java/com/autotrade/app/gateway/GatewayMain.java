package com.autotrade.app.gateway;

import com.autotrade.config.ConfigPaths;
import com.autotrade.config.ProcessStartup;
import java.time.Clock;

/**
 * 시세 게이트웨이(D-013 ②). 1단계는 설정 로딩까지만 하는 진입점이다. 시세 수신·녹화는 2단계(data-engineer). 주문 API는 호출하지 않는다(MD-01).
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class GatewayMain {

    static final String PROCESS = "gateway";

    private GatewayMain() {}

    public static void main(String[] args) {
        System.exit(run(ConfigPaths.defaultLocation(), Clock.systemUTC()));
    }

    /** 기동 절차. 종료 코드를 돌려준다(0: 정상). */
    static int run(ConfigPaths paths, Clock clock) {
        ProcessStartup.start(PROCESS, paths, clock);
        return 0;
    }
}
