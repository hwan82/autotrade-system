package com.autotrade.monitor;

import java.time.Instant;
import java.util.Objects;

/**
 * 임계 수준이 바뀐 사건. 올라갈 때(OK→WARN, WARN→CRITICAL 등)와 돌아올 때 모두 낸다.
 *
 * @param at 수준을 바꾼 표본의 시각
 * @param reading 지표 이름과 대상({@link Reading#name()})
 * @param from 이전 수준
 * @param to 새 수준
 * @param value 수준을 바꾼 표본의 값
 * @param limit 넘은(또는 돌아온) 경계
 * @param unit 단위
 */
public record ThresholdEvent(Instant at, String reading, Level from, Level to, double value, double limit, String unit) {

    public ThresholdEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(reading, "reading");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(unit, "unit");
    }

    /** 나빠지는 쪽 변화인지. */
    public boolean escalation() {
        return to.compareTo(from) > 0;
    }
}
