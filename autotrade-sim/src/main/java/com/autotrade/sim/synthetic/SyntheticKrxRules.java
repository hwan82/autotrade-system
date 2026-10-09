package com.autotrade.sim.synthetic;

import com.autotrade.domain.MarketRules;
import com.autotrade.domain.market.Market;
import com.autotrade.domain.market.Symbol;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 합성 시세 실험용 KRX 규칙. 호가 단위는 KRX 주식 호가 가격 단위(2023-01-25 시행)와 같은 구간이고
 * (출처: 한국거래소 유가증권시장 업무규정 시행세칙 별표, 2단계 KrxMarketRules와 같은 표), 정규장은 평일 09:00~15:30으로
 * 휴장일 캘린더 없이 판단한다. 합성 데이터에는 휴장일이 없어서 충분하다.
 *
 * <p>PR #10(2단계 시세)이 머지되면 녹화 데이터 실험에는 그쪽 {@code KrxMarketRules}(캘린더 포함)를 쓴다. 이 클래스는
 * 합성 데이터 전용으로 남긴다.
 */
public final class SyntheticKrxRules implements MarketRules {

    public static final SyntheticKrxRules INSTANCE = new SyntheticKrxRules();

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long[][] TICKS = {
        {2_000, 1}, {5_000, 5}, {20_000, 10}, {50_000, 50}, {200_000, 100}, {500_000, 500},
    };

    private SyntheticKrxRules() {
    }

    @Override
    public Market market() {
        return Market.KRX;
    }

    @Override
    public ZoneId zone() {
        return SEOUL;
    }

    @Override
    public boolean isRegularSession(Instant instant) {
        ZonedDateTime t = instant.atZone(SEOUL);
        if (t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime lt = t.toLocalTime();
        return !lt.isBefore(LocalTime.of(9, 0)) && lt.isBefore(LocalTime.of(15, 30));
    }

    @Override
    public BigDecimal tickSize(Symbol symbol, BigDecimal price) {
        for (long[] row : TICKS) {
            if (price.compareTo(BigDecimal.valueOf(row[0])) < 0) {
                return BigDecimal.valueOf(row[1]);
            }
        }
        return BigDecimal.valueOf(1_000);
    }
}
