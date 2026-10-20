package com.autotrade.selection.calc;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 한 종목의 장중 순위 구간(그날 정규장 시작 ~ 순위 시각 {@code at} 전) 체결 요약(D-125). 장중 지표({@link Metric.Kind#SESSION})의
 * 입력이다. 체결이 하나도 없는 종목은 이 값을 두지 않는다(지표 NO_DATA).
 *
 * @param first 구간 첫 체결가(보통 시가 단일가 체결)
 * @param last 구간 마지막 체결가
 * @param high 구간 최고 체결가
 * @param low 구간 최저 체결가
 * @param volume 체결 수량 합
 * @param turnover 체결 가격 × 수량 합(원가격)
 * @param trades 체결 건수(1 이상)
 */
public record SessionStats(
        BigDecimal first, BigDecimal last, BigDecimal high, BigDecimal low, long volume, BigDecimal turnover, long trades) {

    public SessionStats {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(turnover, "turnover");
        if (first.signum() <= 0 || last.signum() <= 0 || low.signum() <= 0 || high.compareTo(low) < 0) {
            throw new IllegalArgumentException("체결가 요약이 맞지 않습니다: " + first + " " + last + " " + high + " " + low);
        }
        if (volume <= 0 || trades <= 0 || turnover.signum() <= 0) {
            throw new IllegalArgumentException("체결 수량·건수·거래대금은 0보다 커야 합니다");
        }
    }

    /** 체결 하나로 시작한다. */
    public static SessionStats of(BigDecimal price, long quantity) {
        return new SessionStats(price, price, price, price, quantity, price.multiply(BigDecimal.valueOf(quantity)), 1);
    }

    /** 체결 하나를 더한 새 값. */
    public SessionStats plus(BigDecimal price, long quantity) {
        return new SessionStats(first, price, high.max(price), low.min(price), Math.addExact(volume, quantity),
                turnover.add(price.multiply(BigDecimal.valueOf(quantity))), trades + 1);
    }
}
