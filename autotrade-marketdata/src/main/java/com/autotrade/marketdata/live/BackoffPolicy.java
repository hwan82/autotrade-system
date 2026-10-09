package com.autotrade.marketdata.live;

import java.time.Duration;
import java.util.Objects;

/**
 * 재연결 대기 시간. 처음은 {@code initial}, 실패할 때마다 두 배, {@code max}에서 멈춘다. 난수 지터는 쓰지 않는다
 * (재연결 순서가 테스트에서 결정론적이어야 하고, 연결이 계정당 1~2개라 몰림 위험이 작다 `[추론]`).
 * 상태를 가지므로 소스마다 하나씩 쓴다.
 */
public final class BackoffPolicy {

    private final Duration initial;
    private final Duration max;
    private Duration next;

    public BackoffPolicy(Duration initial, Duration max) {
        this.initial = Objects.requireNonNull(initial, "initial");
        this.max = Objects.requireNonNull(max, "max");
        if (initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("재연결 대기는 0보다 크고 max >= initial 이어야 합니다");
        }
        this.next = initial;
    }

    /** 기본값: 1초에서 시작해 30초까지. 장애표의 "3초 내 전환"은 소스 전환(게이트웨이)이 맡고, 재연결은 그와 별개다. */
    public static BackoffPolicy standard() {
        return new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    public synchronized Duration nextDelay() {
        Duration d = next;
        Duration doubled = next.multipliedBy(2);
        next = doubled.compareTo(max) > 0 ? max : doubled;
        return d;
    }

    public synchronized void reset() {
        next = initial;
    }
}
