package com.autotrade.archfixture;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** ArchitectureTest가 위반을 잡아내는지 확인하는 일부러 잘못된 코드. 운영 코드에서 참조하지 않는다. */
@SuppressWarnings("unused")
public final class ForbiddenTimeAndRandom {

    Object time() {
        return new Object[] {Instant.now(), LocalDate.now(), System.currentTimeMillis(), Clock.systemUTC()};
    }

    Object random() {
        return new Object[] {new Random(), Math.random(), ThreadLocalRandom.current()};
    }

    Object money() {
        return new Object[] {new BigDecimal(0.1), BigDecimal.valueOf(0.1)};
    }

    Object allowed(Clock clock) {
        return new Object[] {Instant.now(clock), new Random(42L)};
    }
}
