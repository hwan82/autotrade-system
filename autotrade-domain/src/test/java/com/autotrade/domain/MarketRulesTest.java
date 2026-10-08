package com.autotrade.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Market;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MarketRulesTest {

    /** 기본 메서드 검증용 가짜 규칙. 실제 호가 단위 수치가 아니다. */
    private static final MarketRules FAKE = new MarketRules() {
        @Override
        public Market market() {
            return Market.KRX;
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Currency currency() {
            return Money.KRW;
        }

        @Override
        public boolean isRegularSession(Instant instant) {
            return false;
        }

        @Override
        public BigDecimal tickSize(BigDecimal price) {
            return new BigDecimal("5");
        }
    };

    @Test
    void 호가_단위_반올림은_호출자가_방향을_정한다() {
        assertThat(FAKE.roundToTick(new BigDecimal("1003"), RoundingMode.DOWN)).isEqualByComparingTo("1000");
        assertThat(FAKE.roundToTick(new BigDecimal("1003"), RoundingMode.UP)).isEqualByComparingTo("1005");
        assertThat(FAKE.roundToTick(new BigDecimal("1005"), RoundingMode.UP)).isEqualByComparingTo("1005");
    }

    @Test
    void 영_이하_가격은_거부한다() {
        assertThatThrownBy(() -> FAKE.roundToTick(BigDecimal.ZERO, RoundingMode.DOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거래일은_거래소_현지_날짜다() {
        // 2026-10-08T15:30Z 는 서울 기준 10월 9일 00:30
        assertThat(FAKE.tradingDate(Instant.parse("2026-10-08T15:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 9));
    }
}
