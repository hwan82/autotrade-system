package com.autotrade.marketdata.live;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트용 시계. */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    void set(Instant t) {
        now = t;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
