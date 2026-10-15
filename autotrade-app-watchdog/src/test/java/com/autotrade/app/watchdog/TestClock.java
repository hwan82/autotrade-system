package com.autotrade.app.watchdog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 시험용으로 앞으로 돌릴 수 있는 시계. */
final class TestClock extends Clock {

    private Instant now;

    TestClock(String start) {
        now = Instant.parse(start);
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
