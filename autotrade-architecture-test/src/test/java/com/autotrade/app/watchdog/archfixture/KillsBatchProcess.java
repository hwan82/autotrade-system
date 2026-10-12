package com.autotrade.app.watchdog.archfixture;

/** 위반 고정 예제: 감시가 PID로 단발 일괄 명령을 종료하려 한다(D-058이 금지, PID 재사용 때 실전 엔진을 죽일 수 있음). */
public final class KillsBatchProcess {

    private KillsBatchProcess() {}

    static void stop(long pid) {
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }

    static void stop(Process p) {
        p.destroy();
    }
}
