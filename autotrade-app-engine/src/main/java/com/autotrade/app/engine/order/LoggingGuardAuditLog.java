package com.autotrade.app.engine.order;

import com.autotrade.risk.guard.GuardAuditLog;
import com.autotrade.risk.guard.GuardTransition;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Guard 전이를 프로세스 로그에 남기는 임시 감사 로그. 영속 저장(DB, 재기동 후 조회)은 실전 엔진 저장소 작업에서 바꾼다.
 */
public final class LoggingGuardAuditLog implements GuardAuditLog {

    private static final Logger LOG = System.getLogger("com.autotrade.engine.guard-audit");

    @Override
    public void record(GuardTransition t) {
        LOG.log(Level.WARNING, "RealTradingGuard {0} {1} → {2} (주체 {3}, 사유 {4}, {5})",
                t.account(), t.from(), t.to(), t.actor(), t.reason(), t.at());
    }
}
