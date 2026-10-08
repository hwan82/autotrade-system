package com.autotrade.domain;

import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;

/**
 * 시장별 차이(시간대, 통화, 거래 시간, 가격 단위, 수량 단위)를 한곳에 모은 계약.
 * 엔진 코드는 시장을 분기하지 않고 이 인터페이스만 쓴다(프로젝트 지침 4장 9항).
 *
 * <p>계약
 * <ul>
 *   <li>구현은 불변이고 스레드 안전해야 한다.</li>
 *   <li>모든 시간 판단은 {@link #zone()}(거래소 현지 시간대) 기준이며, 서머타임·휴장일은 캘린더 데이터로 판단한다.
 *       전환일을 하드코딩하지 않는다.</li>
 *   <li>호가 단위·거래 시간 같은 수치는 공식 출처를 구현 클래스 주석에 남긴다.</li>
 * </ul>
 * 구현(KRX, US)은 2단계(데이터)와 3단계(실험실)에서 추가한다. 가격 제한(상하한가·LULD), 세션 구분(단일가·장외),
 * 허용 주문 유형, 거래일 캘린더(개장·폐장 시각) 조회는 그 단계의 ADR에서 이 계약에 더한다.
 */
public interface MarketRules {

    Market market();

    /** 거래소 현지 시간대. KRX는 Asia/Seoul, US는 America/New_York. */
    ZoneId zone();

    /** 이 시장의 결제 통화. */
    default Currency currency() {
        return market().currency();
    }

    /** 정규장 시간인지. 휴장일·조기 폐장일은 캘린더로 판단한다. */
    boolean isRegularSession(Instant instant);

    /**
     * 종목·가격대의 호가 단위. 가격은 0보다 커야 한다. 종목을 받는 이유: 같은 가격대라도 상품 유형(ETF 등)이나
     * 미국 1달러 미만 종목은 단위가 다를 수 있다.
     */
    BigDecimal tickSize(Symbol symbol, BigDecimal price);

    /** 주문 수량 단위. 1단계는 소수점 매매를 쓰지 않으므로 기본 1주(D-015). */
    default long quantityStep() {
        return 1L;
    }

    /** 거래소 현지 날짜. 멱등 키의 {date}와 일별 집계의 기준이다. */
    default LocalDate tradingDate(Instant instant) {
        return instant.atZone(zone()).toLocalDate();
    }

    /**
     * 가격을 호가 단위에 맞춘다. 반올림 방향은 호출자가 정한다
     * (예: 매수 지정가는 {@link RoundingMode#DOWN}, 매도 지정가는 {@link RoundingMode#UP}).
     */
    default BigDecimal roundToTick(Symbol symbol, BigDecimal price, RoundingMode mode) {
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다: " + price);
        }
        BigDecimal tick = tickSize(symbol, price);
        return price.divide(tick, 0, mode).multiply(tick);
    }
}
