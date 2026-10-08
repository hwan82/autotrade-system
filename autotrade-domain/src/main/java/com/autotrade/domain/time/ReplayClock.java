package com.autotrade.domain.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 리플레이·백테스트용 {@link Clock}. 시세 이벤트 시각에 맞춰 앞으로만 움직인다.
 *
 * <p>비즈니스 로직은 {@code java.time.Clock}만 주입받으므로 실시간({@code Clock.system})과 리플레이가 같은 코드로 돈다.
 * {@link #withZone}으로 만든 시계는 같은 시각 상태를 공유한다. 스레드 안전하다.
 *
 * <p>Run(모의 계좌)마다 시계를 하나씩 만들고 Run끼리 공유하지 않는다. 리플레이 순서는 이벤트 {@code sequence}가 정하고,
 * 시계는 지금까지 본 시각의 최댓값으로 옮긴다({@link #advanceToAtLeast}). 수신 시각이 순서와 어긋나는 이벤트가 있어도
 * Run이 멈추지 않는다.
 */
public final class ReplayClock extends Clock {

    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    public ReplayClock(Instant start, ZoneId zone) {
        this(new AtomicReference<>(Objects.requireNonNull(start, "start")), zone);
    }

    private ReplayClock(AtomicReference<Instant> current, ZoneId zone) {
        this.current = current;
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** 시각을 앞으로 옮긴다. 과거로 돌리려 하면 예외(미래 정보 누수와 순서 뒤바뀜 방지). */
    public void advanceTo(Instant target) {
        Objects.requireNonNull(target, "target");
        current.updateAndGet(now -> {
            if (target.isBefore(now)) {
                throw new IllegalArgumentException("리플레이 시계는 과거로 돌릴 수 없습니다: " + now + " -> " + target);
            }
            return target;
        });
    }

    /** 목표가 현재보다 뒤면 옮기고, 앞이면 그대로 둔다. 이벤트 수신 시각으로 시계를 따라갈 때 쓴다. */
    public void advanceToAtLeast(Instant target) {
        Objects.requireNonNull(target, "target");
        current.accumulateAndGet(target, (now, t) -> t.isAfter(now) ? t : now);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new ReplayClock(current, newZone);
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
