package com.autotrade.sim;

/** 실험 시계 방식. DB experiment.clock_mode와 같은 값. */
public enum ClockMode {
    /** 장중 실시간 시세 위 모의 운용. */
    REALTIME,
    /** 녹화·스냅샷 리플레이. */
    REPLAY
}
