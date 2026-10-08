package com.autotrade.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autotrade.domain.market.Symbol;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    void 형식은_date_code_side_seq() {
        IdempotencyKey key = IdempotencyKey.of(LocalDate.of(2026, 10, 8), Symbol.krx("005930"), Side.BUY, 1);
        assertThat(key).hasToString("20261008-005930-BUY-1");
    }

    @Test
    void 문자열로_되돌리면_같은_키다() {
        IdempotencyKey key = IdempotencyKey.of(LocalDate.of(2026, 10, 8), Symbol.us("BRK.B"), Side.SELL, 12);
        assertThat(IdempotencyKey.parse(key.toString())).isEqualTo(key);
    }

    @Test
    void 잘못된_형식은_거부한다() {
        assertThatThrownBy(() -> IdempotencyKey.parse("20261008-005930-BUY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.parse("20261008-005930-HOLD-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.parse("20261008-005930-BUY-0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.parse("2026-10-08-005930-BUY-1")).isInstanceOf(IllegalArgumentException.class);
    }
}
