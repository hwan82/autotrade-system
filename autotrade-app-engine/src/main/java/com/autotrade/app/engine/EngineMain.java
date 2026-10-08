package com.autotrade.app.engine;

import com.autotrade.app.engine.order.LoggingGuardAuditLog;
import com.autotrade.config.ConfigPaths;
import com.autotrade.config.LoadedConfig;
import com.autotrade.config.ProcessStartup;
import com.autotrade.config.RuntimeConfig;
import com.autotrade.domain.account.AccountRef;
import com.autotrade.risk.guard.RealTradingGuard;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;

/**
 * 실전 매매 엔진(D-013 ①). 지금은 설정 로딩과 RealTradingGuard 생성(모든 실전 계좌 OFF)까지 하는 진입점이다.
 * 계좌별 워커, 주문 큐, 활성화 명령 채널은 7단계 risk-ops 작업.
 *
 * <p>시스템 시계를 만드는 곳은 이 진입점뿐이다. 나머지 코드는 주입받은 {@link Clock}만 쓴다(ArchitectureTest).
 */
public final class EngineMain {

    static final String PROCESS = "engine";

    private static final Logger LOG = System.getLogger("com.autotrade." + PROCESS);

    private EngineMain() {}

    public static void main(String[] args) {
        System.exit(run(ConfigPaths.defaultLocation(), Clock.systemUTC()));
    }

    /** 기동 절차. 종료 코드를 돌려준다(0: 정상). */
    static int run(ConfigPaths paths, Clock clock) {
        LoadedConfig loaded = ProcessStartup.start(PROCESS, paths, clock);
        startGuard(loaded.config(), clock);
        return 0;
    }

    /**
     * 설정의 실전 계좌로 Guard를 만든다. 모든 계좌는 OFF로 시작하며, 설정 어디에도 이를 바꿀 키가 없다(CFG-02, ADR-002).
     */
    static RealTradingGuard startGuard(RuntimeConfig config, Clock clock) {
        RuntimeConfig.RealTrading realTrading = config.realTrading();
        RealTradingGuard guard = new RealTradingGuard(realTrading.accountRefs(), clock, new LoggingGuardAuditLog());
        LOG.log(Level.INFO, "실전 계좌 {0}개, RealTradingGuard 모두 OFF로 시작", guard.accounts().size());
        for (AccountRef account : guard.accounts()) {
            if (!realTrading.isConfigured(account.id().value())) {
                LOG.log(Level.WARNING, "실전 계좌 {0}: 계좌번호가 없어 활성화할 수 없습니다(secrets.json)", account.id());
            }
        }
        return guard;
    }
}
