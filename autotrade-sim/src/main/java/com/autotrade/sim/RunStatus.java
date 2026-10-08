package com.autotrade.sim;

/** 실험 안 계좌 하나(Run)의 상태. DB experiment_run.status와 같은 값. */
public enum RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
