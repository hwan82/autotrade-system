package com.autotrade.monitor;

import java.util.Objects;

/**
 * 지표 한 값. 이름은 {@code 지표[:대상]} 모양이다(예: {@code cpu.system}, {@code disk.used:C:\}). 임계는 콜론 앞 지표 이름으로
 * 찾는다.
 *
 * @param name 지표 이름과 대상
 * @param value 값(유한한 수만. 읽지 못한 값은 수집원이 빼고 돌려준다)
 * @param unit 표시 단위(예: {@code %}, {@code GB}, {@code B/s}, {@code 개})
 */
public record Reading(String name, double value, String unit) {

    public Reading {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unit, "unit");
        if (name.isBlank()) {
            throw new IllegalArgumentException("지표 이름이 비었습니다");
        }
        if (!Threshold.finite(value)) {
            throw new IllegalArgumentException("유한한 값만 받습니다: " + name);
        }
    }

    /** 콜론 앞 지표 이름(임계를 찾는 열쇠). */
    public String metric() {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(0, colon);
    }
}
