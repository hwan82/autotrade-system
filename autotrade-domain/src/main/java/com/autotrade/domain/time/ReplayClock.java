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
