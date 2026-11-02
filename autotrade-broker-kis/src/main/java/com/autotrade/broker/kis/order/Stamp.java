package com.autotrade.broker.kis.order;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 한 순간의 벽시계 시각과 단조 시계 값(나노초, 같은 프로세스 안에서만 비교). 두 시각 사이의 벽시계 경과와 단조 경과가 다르면 그
 * 사이에 PC 시계가 단계로 바뀐 것이다(시간 동기화 보정, 사용자가 시계 변경). risk-ops PR #247 재검토 N1.
 */
public record Stamp(Instant wall, long monoNanos) {

    public Stamp {
        Objects.requireNonNull(wall, "wall");
    }

    /** 이 시각부터 {@code later}까지 단조 경과. */
    public Duration monoUntil(Stamp later) {
        return Duration.ofNanos(later.monoNanos - monoNanos);
    }

    /** 이 시각부터 {@code later}까지 벽시계 경과에서 단조 경과를 뺀 값. 시계가 그 사이 앞으로 뛰었으면 양수. */
    public Duration wallMinusMonoUntil(Stamp later) {
        return Duration.between(wall, later.wall).minus(monoUntil(later));
    }
}
