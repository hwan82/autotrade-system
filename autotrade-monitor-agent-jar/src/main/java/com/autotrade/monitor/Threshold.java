package com.autotrade.monitor;

import java.util.Objects;

/**
 * 지표 하나의 임계. {@link Direction#ABOVE}면 값이 경고 이상일 때 WARN, 위험 이상일 때 CRITICAL이고, {@link Direction#BELOW}면
 * 이하일 때다(예: 디스크 여유 GB). 수준은 같은 판정이 {@code sustain}번 이어져야 바뀐다(한 번 튀는 값으로 알리지 않음). 내려갈
 * 때도 같다.
 *
 * @param metric 지표 이름(콜론 앞, {@link Reading#metric()})
 * @param direction 나빠지는 방향
 * @param warn 경고 경계
 * @param critical 위험 경계(ABOVE면 warn 이상, BELOW면 warn 이하)
 * @param sustain 수준을 바꾸는 데 필요한 연속 판정 수(1 이상)
 */
public record Threshold(String metric, Direction direction, double warn, double critical, int sustain) {

    public enum Direction {
        ABOVE,
        BELOW
    }

    public Threshold {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(direction, "direction");
        if (metric.isBlank() || metric.indexOf(':') >= 0) {
            throw new IllegalArgumentException("임계 지표 이름은 비지 않고 콜론이 없어야 합니다: " + metric);
        }
        if (!finite(warn) || !finite(critical)) {
            throw new IllegalArgumentException("임계 값은 유한해야 합니다: " + metric);
        }
        if (direction == Direction.ABOVE ? critical < warn : critical > warn) {
            throw new IllegalArgumentException("위험 경계가 경고 경계보다 덜 나쁩니다: " + metric);
        }
        if (sustain < 1) {
            throw new IllegalArgumentException("sustain은 1 이상이어야 합니다: " + metric);
        }
    }

    /** 값 하나의 수준(연속 판정 전). */
    public Level levelOf(double value) {
        if (direction == Direction.ABOVE) {
            return value >= critical ? Level.CRITICAL : value >= warn ? Level.WARN : Level.OK;
        }
        return value <= critical ? Level.CRITICAL : value <= warn ? Level.WARN : Level.OK;
    }

    /** 그 수준에 들어서는 경계(OK면 경고 경계: 그 경계를 벗어나 돌아왔다는 뜻). */
    public double limitOf(Level level) {
        return level == Level.CRITICAL ? critical : warn;
    }

    /**
     * 유한한 수인지(NaN·무한대가 아님). {@code Double.isFinite}나 {@code Double} 상수는 클래스 파일에 {@code java.lang.Double}을
     * 남기는데, 이 모듈은 실전 경로 JDK 허용 목록(D-086) 밖 타입을 쓰지 않는다. {@code v - v}는 유한할 때만 0이다.
     */
    static boolean finite(double v) {
        return v - v == 0.0;
    }
}
