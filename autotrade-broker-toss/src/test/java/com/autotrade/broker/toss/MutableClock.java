package com.autotrade.broker.toss;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트용 시계. 잠자기 기록기와 함께 쓰면 기다린 만큼 시간이 간다. */
final class MutableClock extends Clock {

    private Instant now;
    /** 시각을 물을 때마다 먼저 부르는 훅(경합 끼워 넣기용). 훅 안에서 다시 물으면 훅을 부르지 않는다. */
    private Runnable onInstant;
    private boolean inHook;

    MutableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    void onInstant(Runnable hook) {
        this.onInstant = hook;
    }

    @Override
    public Instant instant() {
        if (onInstant != null && !inHook) {
            inHook = true;
            try {
                onInstant.run();
            } finally {
                inHook = false;
            }
        }
        return now;
    }
}
